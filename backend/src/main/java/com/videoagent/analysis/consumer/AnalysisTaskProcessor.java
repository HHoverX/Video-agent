package com.videoagent.analysis.consumer;

import com.videoagent.analysis.dto.AnalysisMessage;
import com.videoagent.analysis.dto.AnalysisProgressSnapshot;
import com.videoagent.analysis.entity.AnalysisStage;
import com.videoagent.analysis.entity.AnalysisStatus;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.analysis.service.AnalysisProperties;
import com.videoagent.analysis.service.AnalysisProgressUpdateService;
import com.videoagent.analysis.service.AnalysisRetryCoordinator;
import com.videoagent.analysis.service.FailureClass;
import com.videoagent.analysis.service.TerminalNotifier;
import com.videoagent.analysis.service.ActiveAnalysisLeaseRegistry;
import com.videoagent.asr.AsrProvider;
import com.videoagent.asr.AudioSource;
import com.videoagent.asr.TranscriptSegment;
import com.videoagent.asr.TranscriptionResult;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.media.AudioExtractResult;
import com.videoagent.media.MediaProcessor;
import com.videoagent.media.MediaWorkspace;
import com.videoagent.media.TemporaryMediaWorkspace;
import com.videoagent.storage.ObjectStorageService;
import com.videoagent.summary.provider.VideoSummaryProvider;
import com.videoagent.summary.provider.VideoSummaryRequest;
import com.videoagent.summary.provider.VideoSummaryDraft;
import com.videoagent.summary.service.VideoSummaryService;
import com.videoagent.transcript.service.TranscriptService;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;
import com.videoagent.rag.service.RagIndexService;
import com.videoagent.telemetry.AnalysisTelemetryContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.OptionalInt;

@Service
public class AnalysisTaskProcessor {

    private static final Logger log = LoggerFactory.getLogger(AnalysisTaskProcessor.class);

    private final AnalysisTaskRepository analysisTaskRepository;
    private final AnalysisProgressUpdateService progressUpdateService;
    private final AnalysisProperties properties;
    private final VideoRepository videoRepository;
    private final ObjectStorageService storageService;
    private final TemporaryMediaWorkspace workspaceFactory;
    private final MediaProcessor mediaProcessor;
    private final AsrProvider asrProvider;
    private final TranscriptService transcriptService;
    private final VideoSummaryProvider summaryProvider;
    private final VideoSummaryService summaryService;
    private final AnalysisRetryCoordinator retryCoordinator;
    private final TerminalNotifier terminalNotifier;
    private final RagIndexService ragIndexService;
    private final ActiveAnalysisLeaseRegistry activeLeases;

    public AnalysisTaskProcessor(
        AnalysisTaskRepository analysisTaskRepository,
        AnalysisProgressUpdateService progressUpdateService,
        AnalysisProperties properties,
        VideoRepository videoRepository,
        ObjectStorageService storageService,
        TemporaryMediaWorkspace workspaceFactory,
        MediaProcessor mediaProcessor,
        AsrProvider asrProvider,
        TranscriptService transcriptService,
        VideoSummaryProvider summaryProvider,
        VideoSummaryService summaryService,
        AnalysisRetryCoordinator retryCoordinator,
        TerminalNotifier terminalNotifier,
        RagIndexService ragIndexService,
        ActiveAnalysisLeaseRegistry activeLeases
    ) {
        this.analysisTaskRepository = analysisTaskRepository;
        this.progressUpdateService = progressUpdateService;
        this.properties = properties;
        this.videoRepository = videoRepository;
        this.storageService = storageService;
        this.workspaceFactory = workspaceFactory;
        this.mediaProcessor = mediaProcessor;
        this.asrProvider = asrProvider;
        this.transcriptService = transcriptService;
        this.summaryProvider = summaryProvider;
        this.summaryService = summaryService;
        this.retryCoordinator = retryCoordinator;
        this.terminalNotifier = terminalNotifier;
        this.ragIndexService = ragIndexService;
        this.activeLeases = activeLeases;
    }

    public void process(AnalysisMessage message) {
        if (message == null || message.taskId() == null || message.videoId() == null) {
            log.warn("[stage=CONSUME] ignoring malformed analysis message");
            return;
        }

        AnalysisTaskEntity task = analysisTaskRepository.selectById(message.taskId());
        if (task == null) {
            log.warn("[taskId={}][videoId={}][stage=CONSUME] task not found; message acknowledged",
                message.taskId(), message.videoId());
            return;
        }
        if (!message.videoId().equals(task.getVideoId())) {
            log.warn("[taskId={}][videoId={}][stage=CONSUME] message videoId does not match task",
                message.taskId(), message.videoId());
            return;
        }
        if (AnalysisStatus.SUCCESS.name().equals(task.getStatus())) {
            log.info("[taskId={}][videoId={}][stage=IDEMPOTENCY] task already SUCCESS; skipping duplicate message",
                task.getId(), task.getVideoId());
            return;
        }
        if (AnalysisStatus.FAILED.name().equals(task.getStatus())) {
            log.info("[taskId={}][videoId={}][stage=IDEMPOTENCY] task already FAILED; skipping duplicate message",
                task.getId(), task.getVideoId());
            return;
        }
        if (!AnalysisStatus.PENDING.name().equals(task.getStatus())
            && !AnalysisStatus.RETRY_WAITING.name().equals(task.getStatus())) {
            log.info("[taskId={}][videoId={}][stage=IDEMPOTENCY] task status={} is not claimable; skipping",
                task.getId(), task.getVideoId(), task.getStatus());
            return;
        }
        if (!properties.analysisType().equals(task.getAnalysisType())
            || !properties.modelVersion().equals(task.getModelVersion())) {
            log.warn("[taskId={}][videoId={}][stage=CONSUME] task version is not handled by this consumer",
                task.getId(), task.getVideoId());
            return;
        }

        int lastProgress = 0;
        Integer activeGeneration = null;
        try {
            // HIGH #2/#4: the claim is a single conditional UPDATE. For
            // RETRY_WAITING it also requires retry_not_before <= now, so a
            // delayed duplicate cannot start the next attempt early. The claim
            // increments processing_generation, which becomes this worker's
            // fencing token for every subsequent write.
            int claimed = analysisTaskRepository.claimPending(
                task.getId(),
                AnalysisStage.PREPARING.name(),
                10,
                LocalDateTime.now()
            );
            if (claimed != 1) {
                log.info("[taskId={}][videoId={}][stage=IDEMPOTENCY] task was claimed by another consumer or backoff not reached",
                    task.getId(), task.getVideoId());
                return;
            }

            // HIGH #4: re-read the persisted task after a successful claim so
            // this worker uses the current generation / retry_count instead of
            // a stale pre-claim snapshot.
            AnalysisTaskEntity current = analysisTaskRepository.selectById(task.getId());
            if (current == null) {
                return;
            }
            task = current;
            int generation = task.getProcessingGeneration() == null ? 0 : task.getProcessingGeneration();
            AnalysisTelemetryContext telemetryContext = new AnalysisTelemetryContext(
                task.getId(), task.getVideoId(), generation, task.getRetryCount()
            );
            activeLeases.register(task.getId(), task.getVideoId(), generation);
            activeGeneration = generation;

            lastProgress = 10;
            publish(task, AnalysisStage.PREPARING, lastProgress);

            VideoEntity video = videoRepository.selectById(task.getVideoId());
            if (video == null) {
                throw new IllegalStateException("Video metadata no longer exists");
            }

            boolean transcriptPersisted = transcriptService.taskHasPersistedSegments(task.getId());
            TranscriptionResult transcription;
            if (transcriptPersisted) {
                populateMissingDuration(task, video);
                List<TranscriptSegment> segments = transcriptService.loadTaskSegments(task.getId());
                transcription = new TranscriptionResult(segments);
                log.info("[taskId={}][videoId={}][generation={}][stage=TRANSCRIPT_SAVED] resuming from persisted transcript",
                    task.getId(), task.getVideoId(), generation);
            } else {
                try (MediaWorkspace workspace = workspaceFactory.create(task.getId())) {
                    storageService.downloadObject(video.getObjectKey(), workspace.videoFile());
                    Integer effectiveDurationSeconds = probeDurationAndPersistIfMissing(video, workspace.videoFile());
                    validateVideoDuration(task, effectiveDurationSeconds);
                    lastProgress = advance(task, generation, AnalysisStage.EXTRACTING_AUDIO, 35);
                    AudioExtractResult audio = mediaProcessor.extractAudio(
                        workspace.videoFile(),
                        workspace.audioFile()
                    );
                    lastProgress = advance(task, generation, AnalysisStage.TRANSCRIBING, 70);
                    log.debug("[taskId={}][videoId={}] ASR AudioSource videoDurationSeconds={}",
                        task.getId(), task.getVideoId(), video.getDurationSeconds());
                    transcription = asrProvider.transcribe(
                        new AudioSource(audio.audioFile(), video.getDurationSeconds()),
                        telemetryContext
                    );
                }
                lastProgress = advance(task, generation, AnalysisStage.TRANSCRIPT_SAVED, 75);
                transcriptService.replaceTaskSegments(task, transcription);
            }

            boolean summaryPersisted = summaryService.taskHasPersistedSummary(task.getId());
            if (summaryPersisted) {
                lastProgress = advance(task, generation, AnalysisStage.SUMMARY_SAVED, 95);
                log.info("[taskId={}][videoId={}][generation={}][stage=SUMMARY_SAVED] resuming from persisted summary",
                    task.getId(), task.getVideoId(), generation);
            } else {
                lastProgress = advance(task, generation, AnalysisStage.SUMMARIZING, 85);
                VideoSummaryRequest summaryRequest = new VideoSummaryRequest(
                    task.getVideoId(),
                    task.getId(),
                    transcription.segments(),
                    telemetryContext
                );
                VideoSummaryDraft summary = summaryProvider.summarize(summaryRequest);
                lastProgress = advance(task, generation, AnalysisStage.SAVING, 95);
                summaryService.replaceTaskResult(task, summaryRequest, summary);
            }

            lastProgress = advance(task, generation, AnalysisStage.INDEXING, 97);
            ragIndexService.ensureAnalysisIndex(task, video.getUserId());
            lastProgress = advance(task, generation, AnalysisStage.INDEX_SAVED, 99);

            int completed = analysisTaskRepository.markSuccess(task.getId(), generation, LocalDateTime.now());
            if (completed != 1) {
                throw new FencingLostException(
                    "Task could not transition to SUCCESS (fencing lost at generation " + generation + ")"
                );
            }
            terminalNotifier.succeeded(task.getId(), task.getVideoId());
            log.info("[taskId={}][videoId={}][generation={}][stage=DONE] structured video summary completed",
                task.getId(), task.getVideoId(), generation);
        } catch (FencingLostException exception) {
            // The recovery (or another worker) already moved the task forward.
            // This worker must not touch the lifecycle any further.
            log.info("[taskId={}][videoId={}][stage=FENCED] worker lost its processing generation; stopping: {}",
                task.getId(), task.getVideoId(), exception.getMessage());
        } catch (VideoAgentException exception) {
            handleFailure(
                task,
                lastProgress,
                exception.errorCode().name(),
                exception.getMessage(),
                exception,
                exception.retryAfter()
            );
        } catch (RuntimeException exception) {
            handleFailure(task, lastProgress, "INTERNAL_ANALYSIS_ERROR", "分析任务内部处理失败", exception);
        } finally {
            if (activeGeneration != null) {
                activeLeases.unregister(task.getId(), activeGeneration);
            }
        }
    }

    private int advance(AnalysisTaskEntity task, int generation, AnalysisStage stage, int progress) {
        int updated = analysisTaskRepository.updateProcessingProgress(
            task.getId(),
            stage.name(),
            progress,
            generation,
            LocalDateTime.now()
        );
        if (updated != 1) {
            throw new FencingLostException(
                "Task progress update was rejected at stage " + stage.name()
                    + " (fencing lost at generation " + generation + ")"
            );
        }
        publish(task, stage, progress);
        return progress;
    }

    private void publish(AnalysisTaskEntity task, AnalysisStage stage, int progress) {
        String status = AnalysisStatus.PROCESSING.name();
        progressUpdateService.update(task.getId(), task.getVideoId(), new AnalysisProgressSnapshot(
            status,
            stage.name(),
            progress,
            stage.message()
        ));
    }

    private void handleFailure(
        AnalysisTaskEntity task,
        int progress,
        String errorCode,
        String message
    ) {
        handleFailure(task, progress, errorCode, message, null, null);
    }

    private void handleFailure(
        AnalysisTaskEntity task,
        int progress,
        String errorCode,
        String message,
        RuntimeException cause
    ) {
        handleFailure(task, progress, errorCode, message, cause, null);
    }

    private void populateMissingDuration(AnalysisTaskEntity task, VideoEntity video) {
        if (video.getDurationSeconds() != null) {
            return;
        }
        try (MediaWorkspace workspace = workspaceFactory.create(task.getId())) {
            storageService.downloadObject(video.getObjectKey(), workspace.videoFile());
            probeDurationAndPersistIfMissing(video, workspace.videoFile());
        } catch (RuntimeException exception) {
            log.warn("[taskId={}][videoId={}][stage=PROBE_DURATION] unable to obtain source media for duration probe: {}",
                task.getId(), task.getVideoId(), exception.getClass().getSimpleName());
        }
    }

    private Integer probeDurationAndPersistIfMissing(VideoEntity video, java.nio.file.Path videoFile) {
        OptionalInt duration = mediaProcessor.probeDurationSeconds(videoFile);
        if (duration.isEmpty()) {
            return null;
        }
        int affectedRows = 0;
        if (video.getDurationSeconds() == null) {
            affectedRows = videoRepository.updateDurationSecondsIfMissing(
                video.getId(), duration.getAsInt(), LocalDateTime.now()
            );
            if (affectedRows == 1) {
                video.setDurationSeconds(duration.getAsInt());
            }
        }
        log.debug("[videoId={}] duration probe probedDurationSeconds={} durationPersistenceAffectedRows={} persistedDurationSeconds={}",
            video.getId(), duration.getAsInt(), affectedRows, video.getDurationSeconds());
        return duration.getAsInt();
    }

    private void validateVideoDuration(AnalysisTaskEntity task, Integer durationSeconds) {
        if (durationSeconds == null || durationSeconds <= properties.maxVideoDuration().toSeconds()) {
            return;
        }
        log.warn("[taskId={}][videoId={}][stage=DURATION_GUARD] durationSeconds={} maxVideoDurationSeconds={}",
            task.getId(), task.getVideoId(), durationSeconds, properties.maxVideoDuration().toSeconds());
        throw new VideoAgentException(
            ErrorCode.ANALYSIS_VIDEO_TOO_LONG,
            "视频时长超过当前 AI 分析支持范围，请缩短视频后重试"
        );
    }

    private void handleFailure(
        AnalysisTaskEntity task,
        int progress,
        String errorCode,
        String message,
        RuntimeException cause,
        java.time.Duration retryAfter
    ) {
        String safeMessage = message == null || message.isBlank() ? "分析任务处理失败" : message;
        if (safeMessage.length() > 1000) {
            safeMessage = safeMessage.substring(0, 1000);
        }
        boolean retryable = FailureClass.of(errorCode) == FailureClass.RETRYABLE;
        if (retryable) {
            String checkpointStage = checkpointStage(task);
            AnalysisRetryCoordinator.RetryOutcome outcome =
                retryCoordinator.handleRetryableFailure(
                    task, checkpointStage, errorCode, safeMessage, retryAfter
                );
            if (outcome == AnalysisRetryCoordinator.RetryOutcome.RETRY_SCHEDULED) {
                progressUpdateService.update(task.getId(), task.getVideoId(), new AnalysisProgressSnapshot(
                    AnalysisStatus.RETRY_WAITING.name(),
                    AnalysisStage.RETRY_WAITING.name(),
                    progress,
                    AnalysisStage.RETRY_WAITING.message()
                ), errorCode, safeMessage);
                log.warn("[taskId={}][videoId={}][generation={}][stage=RETRY_WAITING][errorCode={}][exceptionClass={}] retryable failure recorded",
                    task.getId(), task.getVideoId(), task.getProcessingGeneration(), errorCode, exceptionClass(cause));
            } else if (outcome == AnalysisRetryCoordinator.RetryOutcome.FAILED_TERMINAL) {
                // MEDIUM #6: budget exhausted inside the retry transition.
                terminalNotifier.failed(task.getId(), task.getVideoId(), progress, errorCode, safeMessage);
                log.warn("[taskId={}][videoId={}][generation={}][stage=FAILED][errorCode={}][exceptionClass={}] retry budget exhausted",
                    task.getId(), task.getVideoId(), task.getProcessingGeneration(), errorCode, exceptionClass(cause));
            } else {
                log.info("[taskId={}][videoId={}][generation={}] retry transition lost concurrency; stopping",
                    task.getId(), task.getVideoId(), task.getProcessingGeneration());
            }
        } else {
            // Non-retryable failure. The worker's fence token is used so an
            // abandoned worker can never mark the NEW attempt failed.
            int failed = analysisTaskRepository.markFailedForGeneration(
                task.getId(),
                task.getProcessingGeneration() == null ? 0 : task.getProcessingGeneration(),
                errorCode,
                safeMessage,
                LocalDateTime.now()
            );
            if (failed == 1) {
                terminalNotifier.failed(task.getId(), task.getVideoId(), progress, errorCode, safeMessage);
            } else {
                log.info("[taskId={}][videoId={}][generation={}] non-retryable FAILED transition lost fencing; stopping",
                    task.getId(), task.getVideoId(), task.getProcessingGeneration());
            }
            log.error("[taskId={}][videoId={}][generation={}][retryCount={}][stage=FAILED][errorCode={}][exceptionClass={}] non-retryable analysis failure",
                task.getId(), task.getVideoId(), task.getProcessingGeneration(), task.getRetryCount(), errorCode,
                exceptionClass(cause));
        }
    }

    private String exceptionClass(RuntimeException exception) {
        return exception == null ? "none" : exception.getClass().getSimpleName();
    }

    private String checkpointStage(AnalysisTaskEntity task) {
        if (summaryService.taskHasPersistedSummary(task.getId())) {
            return AnalysisStage.SUMMARY_SAVED.name();
        }
        if (transcriptService.taskHasPersistedSegments(task.getId())) {
            return AnalysisStage.TRANSCRIPT_SAVED.name();
        }
        return task.getStage() == null ? AnalysisStage.PREPARING.name() : task.getStage();
    }

    private static final class FencingLostException extends RuntimeException {
        private FencingLostException(String message) {
            super(message);
        }
    }
}
