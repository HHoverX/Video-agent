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
import com.videoagent.analysis.progress.AnalysisProgressStore;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.analysis.service.AnalysisProperties;
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
    private final AnalysisProgressStore progressStore = mock(AnalysisProgressStore.class);
    private final VideoRepository videoRepository = mock(VideoRepository.class);
    private final ObjectStorageService storageService = mock(ObjectStorageService.class);
    private final TemporaryMediaWorkspace workspaceFactory = mock(TemporaryMediaWorkspace.class);
    private final MediaProcessor mediaProcessor = mock(MediaProcessor.class);
    private final AsrProvider asrProvider = mock(AsrProvider.class);
    private final TranscriptService transcriptService = mock(TranscriptService.class);
    private final MediaWorkspace workspace = mock(MediaWorkspace.class);
    private AnalysisTaskProcessor processor;

    @BeforeEach
    void setUp() {
        AnalysisProperties properties = new AnalysisProperties(
            "VIDEO_ANALYZE_TOPIC",
            "test-consumer",
            "TRANSCRIPTION",
            "m4-ffmpeg-mock-asr-v1",
            Duration.ofHours(24)
        );
        processor = new AnalysisTaskProcessor(
            repository,
            progressStore,
            properties,
            videoRepository,
            storageService,
            workspaceFactory,
            mediaProcessor,
            asrProvider,
            transcriptService
        );
    }

    @Test
    void shouldSkipDuplicateMessageForSuccessfulTask() {
        AnalysisTaskEntity task = taskWithStatus("SUCCESS");
        when(repository.selectById(101L)).thenReturn(task);

        processor.process(new AnalysisMessage(101L, 7L));

        verify(repository).selectById(101L);
        verifyNoMoreInteractions(repository);
        verify(progressStore, never()).save(anyLong(), any());
        verifyNoMoreInteractions(mediaProcessor, asrProvider, transcriptService);
    }

    @Test
    void shouldDownloadExtractTranscribePersistAndCompleteTask() {
        AnalysisTaskEntity task = taskWithStatus("PENDING");
        VideoEntity video = video();
        Path source = Path.of("target", "test-media", "source.mp4").toAbsolutePath();
        Path audio = source.resolveSibling("audio.wav");
        TranscriptionResult transcription = result();

        when(repository.selectById(101L)).thenReturn(task);
        when(repository.claimPending(eq(101L), eq("PREPARING"), eq(10), any(LocalDateTime.class)))
            .thenReturn(1);
        when(repository.updateProcessingProgress(eq(101L), anyString(), anyInt(), any(LocalDateTime.class)))
            .thenReturn(1);
        when(repository.markSuccess(eq(101L), any(LocalDateTime.class))).thenReturn(1);
        when(videoRepository.selectById(7L)).thenReturn(video);
        when(workspaceFactory.create(101L)).thenReturn(workspace);
        when(workspace.videoFile()).thenReturn(source);
        when(workspace.audioFile()).thenReturn(audio);
        when(mediaProcessor.extractAudio(source, audio)).thenReturn(new AudioExtractResult(audio, 128L));
        when(asrProvider.transcribe(new AudioSource(audio))).thenReturn(transcription);

        processor.process(new AnalysisMessage(101L, 7L));

        verify(storageService).downloadObject("videos/demo.mp4", source);
        verify(mediaProcessor).extractAudio(source, audio);
        verify(asrProvider).transcribe(new AudioSource(audio));
        verify(workspace).close();
        verify(transcriptService).replaceTaskSegments(task, transcription);
        verify(repository).markSuccess(eq(101L), any(LocalDateTime.class));

        ArgumentCaptor<AnalysisProgressSnapshot> progressCaptor =
            ArgumentCaptor.forClass(AnalysisProgressSnapshot.class);
        verify(progressStore, times(5)).save(eq(101L), progressCaptor.capture());
        List<AnalysisProgressSnapshot> snapshots = progressCaptor.getAllValues();
        assertThat(snapshots).extracting(AnalysisProgressSnapshot::progress)
            .containsExactly(10, 35, 70, 90, 100);
        assertThat(snapshots).extracting(AnalysisProgressSnapshot::stage)
            .containsExactly("PREPARING", "EXTRACTING_AUDIO", "TRANSCRIBING", "SAVING", "DONE");
        assertThat(snapshots.getLast().status()).isEqualTo("SUCCESS");
    }

    @Test
    void shouldMarkTaskFailedWhenFfmpegFailsAndStillCloseWorkspace() {
        AnalysisTaskEntity task = taskWithStatus("PENDING");
        Path source = Path.of("target", "test-media", "source.mp4").toAbsolutePath();
        Path audio = source.resolveSibling("audio.wav");

        when(repository.selectById(101L)).thenReturn(task);
        when(repository.claimPending(eq(101L), eq("PREPARING"), eq(10), any(LocalDateTime.class)))
            .thenReturn(1);
        when(repository.updateProcessingProgress(eq(101L), eq("EXTRACTING_AUDIO"), eq(35), any(LocalDateTime.class)))
            .thenReturn(1);
        when(repository.markFailed(eq(101L), anyString(), anyString(), any(LocalDateTime.class)))
            .thenReturn(1);
        when(videoRepository.selectById(7L)).thenReturn(video());
        when(workspaceFactory.create(101L)).thenReturn(workspace);
        when(workspace.videoFile()).thenReturn(source);
        when(workspace.audioFile()).thenReturn(audio);
        when(mediaProcessor.extractAudio(source, audio)).thenThrow(
            new VideoAgentException(ErrorCode.FFMPEG_EXECUTION_FAILED, "invalid media")
        );

        processor.process(new AnalysisMessage(101L, 7L));

        verify(workspace).close();
        verify(repository).markFailed(
            eq(101L),
            eq("FFMPEG_EXECUTION_FAILED"),
            eq("invalid media"),
            any(LocalDateTime.class)
        );
        verify(transcriptService, never()).replaceTaskSegments(any(), any());
        verify(repository, never()).markSuccess(anyLong(), any());

        ArgumentCaptor<AnalysisProgressSnapshot> snapshot =
            ArgumentCaptor.forClass(AnalysisProgressSnapshot.class);
        verify(progressStore, times(3)).save(eq(101L), snapshot.capture());
        assertThat(snapshot.getValue().status()).isEqualTo("FAILED");
        assertThat(snapshot.getValue().progress()).isEqualTo(35);
    }

    @Test
    void shouldSkipWhenAnotherConsumerAlreadyClaimedPendingTask() {
        AnalysisTaskEntity task = taskWithStatus("PENDING");
        when(repository.selectById(101L)).thenReturn(task);
        when(repository.claimPending(eq(101L), eq("PREPARING"), eq(10), any(LocalDateTime.class)))
            .thenReturn(0);

        processor.process(new AnalysisMessage(101L, 7L));

        verify(repository, never()).updateProcessingProgress(anyLong(), anyString(), anyInt(), any());
        verify(repository, never()).markSuccess(anyLong(), any());
        verify(progressStore, never()).save(anyLong(), any());
        verifyNoMoreInteractions(mediaProcessor, asrProvider, transcriptService);
    }

    private AnalysisTaskEntity taskWithStatus(String status) {
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setId(101L);
        task.setVideoId(7L);
        task.setAnalysisType("TRANSCRIPTION");
        task.setModelVersion("m4-ffmpeg-mock-asr-v1");
        task.setStatus(status);
        task.setStage(status.equals("SUCCESS") ? "DONE" : "QUEUED");
        task.setProgress(status.equals("SUCCESS") ? 100 : 0);
        return task;
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
}
