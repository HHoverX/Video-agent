package com.videoagent.analysis.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.videoagent.analysis.dto.AnalysisMessage;
import com.videoagent.analysis.dto.AnalysisProgressSnapshot;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.analysis.service.AnalysisProperties;
import com.videoagent.analysis.service.AnalysisProgressUpdateService;
import com.videoagent.analysis.service.AnalysisRetryCoordinator;
import com.videoagent.analysis.service.AnalysisRetryCoordinator.RetryOutcome;
import com.videoagent.analysis.service.TerminalNotifier;
import com.videoagent.asr.AsrProvider;
import com.videoagent.asr.AudioSource;
import com.videoagent.asr.TranscriptSegment;
import com.videoagent.asr.TranscriptionResult;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.media.AudioExtractResult;
import com.videoagent.media.MediaProcessor;
import com.videoagent.media.MediaWorkspace;
import com.videoagent.media.TemporaryMediaWorkspace;
import com.videoagent.storage.ObjectStorageService;
import com.videoagent.summary.provider.SummaryChapter;
import com.videoagent.summary.provider.SummaryKeyPoint;
import com.videoagent.summary.provider.VideoSummaryProvider;
import com.videoagent.summary.provider.VideoSummaryRequest;
import com.videoagent.summary.provider.VideoSummaryResult;
import com.videoagent.summary.service.VideoSummaryService;
import com.videoagent.transcript.service.TranscriptService;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

class AnalysisTaskProcessorTest {

    private final AnalysisTaskRepository repository = mock(AnalysisTaskRepository.class);
    private final AnalysisProgressUpdateService progressUpdateService = mock(AnalysisProgressUpdateService.class);
    private final VideoRepository videoRepository = mock(VideoRepository.class);
    private final ObjectStorageService storageService = mock(ObjectStorageService.class);
    private final TemporaryMediaWorkspace workspaceFactory = mock(TemporaryMediaWorkspace.class);
    private final MediaProcessor mediaProcessor = mock(MediaProcessor.class);
    private final AsrProvider asrProvider = mock(AsrProvider.class);
    private final TranscriptService transcriptService = mock(TranscriptService.class);
    private final VideoSummaryProvider summaryProvider = mock(VideoSummaryProvider.class);
    private final VideoSummaryService summaryService = mock(VideoSummaryService.class);
    private final AnalysisRetryCoordinator retryCoordinator = mock(AnalysisRetryCoordinator.class);
    private final TerminalNotifier terminalNotifier = mock(TerminalNotifier.class);
    private final MediaWorkspace workspace = mock(MediaWorkspace.class);
    private AnalysisTaskProcessor processor;

    @BeforeEach
    void setUp() {
        AnalysisProperties properties = new AnalysisProperties(
            "VIDEO_ANALYZE_TOPIC",
            "test-consumer",
            "STRUCTURED_SUMMARY",
            "m5-langchain4j-structured-v1",
            Duration.ofHours(24)
        );
        processor = new AnalysisTaskProcessor(
            repository,
            progressUpdateService,
            properties,
            videoRepository,
            storageService,
            workspaceFactory,
            mediaProcessor,
            asrProvider,
            transcriptService,
            summaryProvider,
            summaryService,
            retryCoordinator,
            terminalNotifier
        );
    }

    @Test
    void shouldSkipDuplicateMessageForSuccessfulTask() {
        AnalysisTaskEntity task = taskWithStatus("SUCCESS");
        when(repository.selectById(101L)).thenReturn(task);

        processor.process(new AnalysisMessage(101L, 7L));

        verify(repository).selectById(101L);
        verifyNoMoreInteractions(repository);
        verify(progressUpdateService, never()).update(anyLong(), anyLong(), any());
        verifyNoMoreInteractions(mediaProcessor, asrProvider, transcriptService, summaryProvider, summaryService);
    }

    @Test
    void shouldSkipDuplicateMessageForFailedTask() {
        AnalysisTaskEntity task = taskWithStatus("FAILED");
        when(repository.selectById(101L)).thenReturn(task);

        processor.process(new AnalysisMessage(101L, 7L));

        verify(repository).selectById(101L);
        verify(progressUpdateService, never()).update(anyLong(), anyLong(), any());
    }

    @Test
    void shouldDownloadExtractTranscribePersistAndCompleteTask() {
        AnalysisTaskEntity task = taskWithStatus("PENDING");
        AnalysisTaskEntity claimed = claimedTask(task);
        VideoEntity video = video();
        Path source = Path.of("target", "test-media", "source.mp4").toAbsolutePath();
        Path audio = source.resolveSibling("audio.wav");
        TranscriptionResult transcription = result();

        when(repository.selectById(101L)).thenReturn(task, claimed);
        when(repository.claimPending(eq(101L), eq("PREPARING"), eq(10), any(LocalDateTime.class)))
            .thenReturn(1);
        when(repository.updateProcessingProgress(eq(101L), anyString(), anyInt(), eq(1), any(LocalDateTime.class)))
            .thenReturn(1);
        when(repository.markSuccess(eq(101L), eq(1), any(LocalDateTime.class))).thenReturn(1);
        when(videoRepository.selectById(7L)).thenReturn(video);
        when(workspaceFactory.create(101L)).thenReturn(workspace);
        when(workspace.videoFile()).thenReturn(source);
        when(workspace.audioFile()).thenReturn(audio);
        when(mediaProcessor.extractAudio(source, audio)).thenReturn(new AudioExtractResult(audio, 128L));
        when(asrProvider.transcribe(new AudioSource(audio))).thenReturn(transcription);
        when(summaryProvider.summarize(any())).thenReturn(summaryResult());
        when(transcriptService.taskHasPersistedSegments(101L)).thenReturn(false);
        when(summaryService.taskHasPersistedSummary(101L)).thenReturn(false);

        processor.process(new AnalysisMessage(101L, 7L));

        verify(storageService).downloadObject("videos/demo.mp4", source);
        verify(mediaProcessor).extractAudio(source, audio);
        verify(asrProvider).transcribe(new AudioSource(audio));
        verify(workspace).close();
        verify(transcriptService).replaceTaskSegments(claimed, transcription);
        verify(summaryProvider).summarize(any());
        verify(summaryService).replaceTaskResult(eq(claimed), any(), eq(summaryResult()));
        verify(repository).markSuccess(eq(101L), eq(1), any(LocalDateTime.class));
        verify(terminalNotifier).succeeded(101L, 7L);

        ArgumentCaptor<AnalysisProgressSnapshot> progressCaptor =
            ArgumentCaptor.forClass(AnalysisProgressSnapshot.class);
        verify(progressUpdateService, times(6)).update(eq(101L), eq(7L), progressCaptor.capture());
        List<AnalysisProgressSnapshot> snapshots = progressCaptor.getAllValues();
        assertThat(snapshots).extracting(AnalysisProgressSnapshot::progress)
            .containsExactly(10, 35, 70, 75, 85, 95);
        assertThat(snapshots).extracting(AnalysisProgressSnapshot::stage)
            .containsExactly(
                "PREPARING", "EXTRACTING_AUDIO", "TRANSCRIBING", "TRANSCRIPT_SAVED",
                "SUMMARIZING", "SAVING"
            );
    }

    @Test
    void shouldResumeFromPersistedTranscriptWithoutCallingAsr() {
        AnalysisTaskEntity task = taskWithStatus("RETRY_WAITING");
        task.setRetryCount(1);
        VideoEntity video = video();
        TranscriptionResult transcription = result();

        when(repository.selectById(101L)).thenReturn(task, claimedTask(task));
        when(repository.claimPending(eq(101L), eq("PREPARING"), eq(10), any(LocalDateTime.class)))
            .thenReturn(1);
        when(repository.updateProcessingProgress(eq(101L), anyString(), anyInt(), eq(1), any(LocalDateTime.class)))
            .thenReturn(1);
        when(repository.markSuccess(eq(101L), eq(1), any(LocalDateTime.class))).thenReturn(1);
        when(videoRepository.selectById(7L)).thenReturn(video);
        when(transcriptService.taskHasPersistedSegments(101L)).thenReturn(true);
        when(transcriptService.loadTaskSegments(101L))
            .thenReturn(List.of(
                new TranscriptSegment(0, 2_000, "segment one"),
                new TranscriptSegment(2_000, 4_000, "segment two")
            ));
        when(summaryService.taskHasPersistedSummary(101L)).thenReturn(false);
        when(summaryProvider.summarize(any())).thenReturn(summaryResult());

        processor.process(new AnalysisMessage(101L, 7L));

        verify(asrProvider, never()).transcribe(any());
        verify(mediaProcessor, never()).extractAudio(any(), any());
        verify(storageService, never()).downloadObject(anyString(), any());
        verify(transcriptService, never()).replaceTaskSegments(any(), any());
        verify(summaryProvider).summarize(any());
        verify(repository).markSuccess(eq(101L), eq(1), any(LocalDateTime.class));
    }

    @Test
    void shouldNotCreateDuplicateSummaryWhenSummaryAlreadyPersisted() {
        AnalysisTaskEntity task = taskWithStatus("RETRY_WAITING");
        task.setRetryCount(1);
        VideoEntity video = video();

        when(repository.selectById(101L)).thenReturn(task, claimedTask(task));
        when(repository.claimPending(eq(101L), eq("PREPARING"), eq(10), any(LocalDateTime.class)))
            .thenReturn(1);
        when(repository.updateProcessingProgress(eq(101L), anyString(), anyInt(), eq(1), any(LocalDateTime.class)))
            .thenReturn(1);
        when(repository.markSuccess(eq(101L), eq(1), any(LocalDateTime.class))).thenReturn(1);
        when(videoRepository.selectById(7L)).thenReturn(video);
        when(transcriptService.taskHasPersistedSegments(101L)).thenReturn(true);
        when(transcriptService.loadTaskSegments(101L))
            .thenReturn(List.of(new TranscriptSegment(0, 2_000, "segment one")));
        when(summaryService.taskHasPersistedSummary(101L)).thenReturn(true);

        processor.process(new AnalysisMessage(101L, 7L));

        verify(summaryProvider, never()).summarize(any());
        verify(summaryService, never()).replaceTaskResult(any(), any(), any());
        verify(repository).markSuccess(eq(101L), eq(1), any(LocalDateTime.class));
    }

    @Test
    void shouldMarkTaskFailedWhenFfmpegFailsAndStillCloseWorkspace() {
        AnalysisTaskEntity task = taskWithStatus("PENDING");
        Path source = Path.of("target", "test-media", "source.mp4").toAbsolutePath();
        Path audio = source.resolveSibling("audio.wav");

        when(repository.selectById(101L)).thenReturn(task, claimedTask(task));
        when(repository.claimPending(eq(101L), eq("PREPARING"), eq(10), any(LocalDateTime.class)))
            .thenReturn(1);
        when(repository.updateProcessingProgress(eq(101L), eq("EXTRACTING_AUDIO"), eq(35), eq(1), any(LocalDateTime.class)))
            .thenReturn(1);
        when(repository.markFailedForGeneration(
            eq(101L), eq(1), eq("FFMPEG_EXECUTION_FAILED"), eq("invalid media"), any(LocalDateTime.class)
        )).thenReturn(1);
        when(videoRepository.selectById(7L)).thenReturn(video());
        when(workspaceFactory.create(101L)).thenReturn(workspace);
        when(workspace.videoFile()).thenReturn(source);
        when(workspace.audioFile()).thenReturn(audio);
        when(transcriptService.taskHasPersistedSegments(101L)).thenReturn(false);
        when(mediaProcessor.extractAudio(source, audio)).thenThrow(
            new VideoAgentException(ErrorCode.FFMPEG_EXECUTION_FAILED, "invalid media")
        );

        processor.process(new AnalysisMessage(101L, 7L));

        verify(workspace).close();
        verify(repository).markFailedForGeneration(
            eq(101L), eq(1), eq("FFMPEG_EXECUTION_FAILED"), eq("invalid media"), any(LocalDateTime.class)
        );
        verify(terminalNotifier).failed(eq(101L), eq(7L), anyInt(), eq("FFMPEG_EXECUTION_FAILED"), eq("invalid media"));
        verify(transcriptService, never()).replaceTaskSegments(any(), any());
        verify(summaryProvider, never()).summarize(any());
        verify(repository, never()).markSuccess(anyLong(), anyInt(), any());
    }

    @Test
    void shouldRouteRetryableProviderFailureThroughCoordinator() {
        AnalysisTaskEntity task = taskWithStatus("PENDING");
        AnalysisTaskEntity claimed = claimedTask(task);
        Path source = Path.of("target", "test-media", "source.mp4").toAbsolutePath();
        Path audio = source.resolveSibling("audio.wav");
        TranscriptionResult transcription = result();

        when(repository.selectById(101L)).thenReturn(task, claimed);
        when(repository.claimPending(eq(101L), eq("PREPARING"), eq(10), any(LocalDateTime.class)))
            .thenReturn(1);
        when(repository.updateProcessingProgress(eq(101L), anyString(), anyInt(), eq(1), any(LocalDateTime.class)))
            .thenReturn(1);
        when(videoRepository.selectById(7L)).thenReturn(video());
        when(workspaceFactory.create(101L)).thenReturn(workspace);
        when(workspace.videoFile()).thenReturn(source);
        when(workspace.audioFile()).thenReturn(audio);
        when(transcriptService.taskHasPersistedSegments(101L)).thenReturn(false);
        when(mediaProcessor.extractAudio(source, audio)).thenReturn(new AudioExtractResult(audio, 128L));
        when(asrProvider.transcribe(new AudioSource(audio))).thenReturn(transcription);
        when(summaryProvider.summarize(any())).thenThrow(
            new VideoAgentException(ErrorCode.LLM_SUMMARY_FAILED, "provider unavailable")
        );
        when(retryCoordinator.handleRetryableFailure(eq(claimed), anyString(), eq("LLM_SUMMARY_FAILED"), eq("provider unavailable")))
            .thenReturn(RetryOutcome.RETRY_SCHEDULED);

        processor.process(new AnalysisMessage(101L, 7L));

        verify(transcriptService).replaceTaskSegments(claimed, transcription);
        verify(retryCoordinator).handleRetryableFailure(eq(claimed), anyString(), eq("LLM_SUMMARY_FAILED"), eq("provider unavailable"));
        verify(repository, never()).markFailedForGeneration(anyLong(), anyInt(), anyString(), anyString(), any());
        verify(repository, never()).markSuccess(anyLong(), anyInt(), any());
    }

    @Test
    void shouldPublishFailedTerminalWhenRetryBudgetExhausted() {
        AnalysisTaskEntity task = taskWithStatus("PENDING");
        task.setRetryCount(2);
        AnalysisTaskEntity claimed = claimedTask(task);
        Path source = Path.of("target", "test-media", "source.mp4").toAbsolutePath();
        Path audio = source.resolveSibling("audio.wav");
        TranscriptionResult transcription = result();

        when(repository.selectById(101L)).thenReturn(task, claimed);
        when(repository.claimPending(eq(101L), eq("PREPARING"), eq(10), any(LocalDateTime.class)))
            .thenReturn(1);
        when(repository.updateProcessingProgress(eq(101L), anyString(), anyInt(), eq(1), any(LocalDateTime.class)))
            .thenReturn(1);
        when(videoRepository.selectById(7L)).thenReturn(video());
        when(workspaceFactory.create(101L)).thenReturn(workspace);
        when(workspace.videoFile()).thenReturn(source);
        when(workspace.audioFile()).thenReturn(audio);
        when(transcriptService.taskHasPersistedSegments(101L)).thenReturn(false);
        when(mediaProcessor.extractAudio(source, audio)).thenReturn(new AudioExtractResult(audio, 128L));
        when(asrProvider.transcribe(new AudioSource(audio))).thenReturn(transcription);
        when(summaryProvider.summarize(any())).thenThrow(
            new VideoAgentException(ErrorCode.LLM_SUMMARY_FAILED, "provider unavailable")
        );
        when(retryCoordinator.handleRetryableFailure(eq(claimed), anyString(), eq("LLM_SUMMARY_FAILED"), eq("provider unavailable")))
            .thenReturn(RetryOutcome.FAILED_TERMINAL);

        processor.process(new AnalysisMessage(101L, 7L));

        verify(terminalNotifier).failed(eq(101L), eq(7L), anyInt(), eq("LLM_SUMMARY_FAILED"), eq("provider unavailable"));
    }

    @Test
    void shouldNotRetryProgrammingErrorsLikeNullPointerException() {
        AnalysisTaskEntity task = taskWithStatus("PENDING");
        AnalysisTaskEntity claimed = claimedTask(task);
        VideoEntity video = video();
        Path source = Path.of("target", "test-media", "source.mp4").toAbsolutePath();
        Path audio = source.resolveSibling("audio.wav");
        TranscriptionResult transcription = result();

        when(repository.selectById(101L)).thenReturn(task, claimed);
        when(repository.claimPending(eq(101L), eq("PREPARING"), eq(10), any(LocalDateTime.class)))
            .thenReturn(1);
        when(repository.updateProcessingProgress(eq(101L), anyString(), anyInt(), eq(1), any(LocalDateTime.class)))
            .thenReturn(1);
        when(repository.markFailedForGeneration(
            eq(101L), eq(1), eq("INTERNAL_ANALYSIS_ERROR"), eq("unexpected programming error"), any(LocalDateTime.class)
        )).thenReturn(1);
        when(videoRepository.selectById(7L)).thenReturn(video);
        when(workspaceFactory.create(101L)).thenReturn(workspace);
        when(workspace.videoFile()).thenReturn(source);
        when(workspace.audioFile()).thenReturn(audio);
        when(transcriptService.taskHasPersistedSegments(101L)).thenReturn(false);
        when(mediaProcessor.extractAudio(source, audio)).thenReturn(new AudioExtractResult(audio, 128L));
        when(asrProvider.transcribe(new AudioSource(audio))).thenReturn(transcription);
        when(summaryProvider.summarize(any())).thenThrow(
            new NullPointerException("unexpected programming error")
        );

        processor.process(new AnalysisMessage(101L, 7L));

        verify(transcriptService).replaceTaskSegments(claimed, transcription);
        verify(repository).markFailedForGeneration(
            eq(101L), eq(1), eq("INTERNAL_ANALYSIS_ERROR"), eq("unexpected programming error"), any(LocalDateTime.class)
        );
        verify(retryCoordinator, never()).handleRetryableFailure(any(), any(), any(), any());
        verify(repository, never()).markSuccess(anyLong(), anyInt(), any());
        verify(terminalNotifier).failed(eq(101L), eq(7L), anyInt(), eq("INTERNAL_ANALYSIS_ERROR"), eq("unexpected programming error"));
    }

    @Test
    void shouldStopSilentlyWhenFencingLost() {
        AnalysisTaskEntity task = taskWithStatus("PENDING");
        VideoEntity video = video();
        Path source = Path.of("target", "test-media", "source.mp4").toAbsolutePath();
        Path audio = source.resolveSibling("audio.wav");
        TranscriptionResult transcription = result();

        when(repository.selectById(101L)).thenReturn(task, claimedTask(task));
        when(repository.claimPending(eq(101L), eq("PREPARING"), eq(10), any(LocalDateTime.class)))
            .thenReturn(1);
        // First advance succeeds, then the worker loses its fence (recovery
        // incremented generation), so the second advance returns 0.
        when(repository.updateProcessingProgress(eq(101L), eq("EXTRACTING_AUDIO"), eq(35), eq(1), any(LocalDateTime.class)))
            .thenReturn(1);
        when(repository.updateProcessingProgress(eq(101L), eq("TRANSCRIBING"), eq(70), eq(1), any(LocalDateTime.class)))
            .thenReturn(0);
        when(videoRepository.selectById(7L)).thenReturn(video);
        when(workspaceFactory.create(101L)).thenReturn(workspace);
        when(workspace.videoFile()).thenReturn(source);
        when(workspace.audioFile()).thenReturn(audio);
        when(transcriptService.taskHasPersistedSegments(101L)).thenReturn(false);
        when(mediaProcessor.extractAudio(source, audio)).thenReturn(new AudioExtractResult(audio, 128L));
        when(asrProvider.transcribe(new AudioSource(audio))).thenReturn(transcription);

        processor.process(new AnalysisMessage(101L, 7L));

        verify(repository, never()).markSuccess(anyLong(), anyInt(), any());
        verify(repository, never()).markFailedForGeneration(anyLong(), anyInt(), anyString(), anyString(), any());
        verify(retryCoordinator, never()).handleRetryableFailure(any(), any(), any(), any());
        verify(terminalNotifier, never()).failed(anyLong(), anyLong(), anyInt(), anyString(), anyString());
    }

    @Test
    void shouldSkipWhenAnotherConsumerAlreadyClaimedPendingTask() {
        AnalysisTaskEntity task = taskWithStatus("PENDING");
        when(repository.selectById(101L)).thenReturn(task);
        when(repository.claimPending(eq(101L), eq("PREPARING"), eq(10), any(LocalDateTime.class)))
            .thenReturn(0);

        processor.process(new AnalysisMessage(101L, 7L));

        verify(repository, never()).updateProcessingProgress(anyLong(), anyString(), anyInt(), anyInt(), any());
        verify(repository, never()).markSuccess(anyLong(), anyInt(), any());
        verify(progressUpdateService, never()).update(anyLong(), anyLong(), any());
        verifyNoMoreInteractions(mediaProcessor, asrProvider, transcriptService, summaryProvider, summaryService);
    }

    private AnalysisTaskEntity taskWithStatus(String status) {
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setId(101L);
        task.setVideoId(7L);
        task.setAnalysisType("STRUCTURED_SUMMARY");
        task.setModelVersion("m5-langchain4j-structured-v1");
        task.setStatus(status);
        task.setStage(status.equals("SUCCESS") ? "DONE" : "QUEUED");
        task.setProgress(status.equals("SUCCESS") ? 100 : 0);
        task.setRetryCount(0);
        task.setProcessingGeneration(0);
        return task;
    }

    private AnalysisTaskEntity claimedTask(AnalysisTaskEntity original) {
        AnalysisTaskEntity claimed = new AnalysisTaskEntity();
        claimed.setId(original.getId());
        claimed.setVideoId(original.getVideoId());
        claimed.setAnalysisType(original.getAnalysisType());
        claimed.setModelVersion(original.getModelVersion());
        claimed.setStatus("PROCESSING");
        claimed.setStage(original.getStage());
        claimed.setProgress(original.getProgress() == null ? 0 : original.getProgress());
        claimed.setRetryCount(original.getRetryCount());
        claimed.setProcessingGeneration(1);
        return claimed;
    }

    private VideoEntity video() {
        VideoEntity video = new VideoEntity();
        video.setId(7L);
        video.setObjectKey("videos/demo.mp4");
        return video;
    }

    private TranscriptionResult result() {
        return new TranscriptionResult(List.of(
            new TranscriptSegment(0, 2_000, "segment one"),
            new TranscriptSegment(2_000, 4_000, "segment two")
        ));
    }

    private VideoSummaryResult summaryResult() {
        return new VideoSummaryResult(
            "overview",
            List.of(new SummaryChapter("chapter", "chapter summary", 0, 4_000)),
            List.of(new SummaryKeyPoint("key point", 0, 2_000))
        );
    }
}
