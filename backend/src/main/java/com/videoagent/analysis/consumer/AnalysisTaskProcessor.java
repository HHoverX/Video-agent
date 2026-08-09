package com.videoagent.analysis.consumer;

import com.videoagent.analysis.dto.AnalysisMessage;
import com.videoagent.analysis.dto.AnalysisProgressSnapshot;
import com.videoagent.analysis.entity.AnalysisStage;
import com.videoagent.analysis.entity.AnalysisStatus;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.analysis.service.AnalysisProperties;
import com.videoagent.analysis.service.AnalysisProgressUpdateService;
import com.videoagent.asr.AsrProvider;
import com.videoagent.asr.AudioSource;
import com.videoagent.asr.TranscriptionResult;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.media.AudioExtractResult;
import com.videoagent.media.MediaProcessor;
import com.videoagent.media.MediaWorkspace;
import com.videoagent.media.TemporaryMediaWorkspace;
import com.videoagent.storage.ObjectStorageService;
import com.videoagent.summary.provider.VideoSummaryProvider;
import com.videoagent.summary.provider.VideoSummaryRequest;
import com.videoagent.summary.provider.VideoSummaryResult;
import com.videoagent.summary.service.VideoSummaryService;
import com.videoagent.transcript.service.TranscriptService;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

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
        VideoSummaryService summaryService
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
        if (!AnalysisStatus.PENDING.name().equals(task.getStatus())) {
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

        int lastProgress = task.getProgress() == null ? 0 : task.getProgress();
        try {
            int claimed = analysisTaskRepository.claimPending(
                task.getId(),
                AnalysisStage.PREPARING.name(),
                10,
                LocalDateTime.now()
            );
            if (claimed != 1) {
                log.info("[taskId={}][videoId={}][stage=IDEMPOTENCY] task was claimed by another consumer",
                    task.getId(), task.getVideoId());
                return;
            }

            lastProgress = 10;
            publish(task, AnalysisStage.PREPARING, lastProgress);

            VideoEntity video = videoRepository.selectById(task.getVideoId());
            if (video == null) {
                throw new IllegalStateException("Video metadata no longer exists");
            }

            TranscriptionResult transcription;
            try (MediaWorkspace workspace = workspaceFactory.create(task.getId())) {
                storageService.downloadObject(video.getObjectKey(), workspace.videoFile());
                lastProgress = advance(task, AnalysisStage.EXTRACTING_AUDIO, 35);
                AudioExtractResult audio = mediaProcessor.extractAudio(
                    workspace.videoFile(),
                    workspace.audioFile()
                );
                lastProgress = advance(task, AnalysisStage.TRANSCRIBING, 70);
                transcription = asrProvider.transcribe(new AudioSource(audio.audioFile()));
            }

            lastProgress = advance(task, AnalysisStage.SAVING_TRANSCRIPT, 75);
            transcriptService.replaceTaskSegments(task, transcription);

            VideoSummaryRequest summaryRequest = new VideoSummaryRequest(
                task.getVideoId(),
                task.getId(),
                transcription.segments()
            );
            lastProgress = advance(task, AnalysisStage.SUMMARIZING, 85);
            VideoSummaryResult summary = summaryProvider.summarize(summaryRequest);

            lastProgress = advance(task, AnalysisStage.SAVING, 95);
            summaryService.replaceTaskResult(task, summaryRequest, summary);

            int completed = analysisTaskRepository.markSuccess(task.getId(), LocalDateTime.now());
            if (completed != 1) {
                throw new IllegalStateException("Task could not transition to SUCCESS");
            }
            publish(task, AnalysisStage.DONE, 100);
            log.info("[taskId={}][videoId={}][stage=DONE] structured video summary completed",
                task.getId(), task.getVideoId());
        } catch (VideoAgentException exception) {
            fail(task, lastProgress, exception.errorCode().name(), exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            fail(task, lastProgress, "ANALYSIS_PROCESSING_FAILED", exception.getMessage(), exception);
        }
    }

    private int advance(AnalysisTaskEntity task, AnalysisStage stage, int progress) {
        int updated = analysisTaskRepository.updateProcessingProgress(
            task.getId(),
            stage.name(),
            progress,
            LocalDateTime.now()
        );
        if (updated != 1) {
            throw new IllegalStateException("Task progress update was rejected at stage " + stage.name());
        }
        publish(task, stage, progress);
        return progress;
    }

    private void publish(AnalysisTaskEntity task, AnalysisStage stage, int progress) {
        String status = stage == AnalysisStage.DONE
            ? AnalysisStatus.SUCCESS.name()
            : AnalysisStatus.PROCESSING.name();
        progressUpdateService.update(task.getId(), task.getVideoId(), new AnalysisProgressSnapshot(
            status,
            stage.name(),
            progress,
            stage.message()
        ));
    }

    private void fail(
        AnalysisTaskEntity task,
        int progress,
        String errorCode,
        String message,
        Exception exception
    ) {
        String safeMessage = message == null || message.isBlank() ? "分析任务处理失败" : message;
        if (safeMessage.length() > 1000) {
            safeMessage = safeMessage.substring(0, 1000);
        }
        analysisTaskRepository.markFailed(task.getId(), errorCode, safeMessage, LocalDateTime.now());
        progressUpdateService.update(task.getId(), task.getVideoId(), new AnalysisProgressSnapshot(
            AnalysisStatus.FAILED.name(),
            AnalysisStage.FAILED.name(),
            progress,
            safeMessage
        ), errorCode, safeMessage);
        log.error("[taskId={}][videoId={}][stage=FAILED] media transcription failed",
            task.getId(), task.getVideoId(), exception);
    }
}
