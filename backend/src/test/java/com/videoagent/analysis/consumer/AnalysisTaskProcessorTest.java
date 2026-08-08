package com.videoagent.analysis.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

class AnalysisTaskProcessorTest {

    private final AnalysisTaskRepository repository = mock(AnalysisTaskRepository.class);
    private final AnalysisProgressStore progressStore = mock(AnalysisProgressStore.class);
    private AnalysisTaskProcessor processor;

    @BeforeEach
    void setUp() {
        AnalysisProperties properties = new AnalysisProperties(
            "VIDEO_ANALYZE_TOPIC",
            "test-consumer",
            "FRAMEWORK",
            "m3-simulation-v1",
            Duration.ofHours(24),
            Duration.ZERO
        );
        processor = new AnalysisTaskProcessor(repository, progressStore, properties);
    }

    @Test
    void shouldSkipDuplicateMessageForSuccessfulTask() {
        AnalysisTaskEntity task = taskWithStatus("SUCCESS");
        when(repository.selectById(101L)).thenReturn(task);

        processor.process(new AnalysisMessage(101L, 7L));

        verify(repository).selectById(101L);
        verifyNoMoreInteractions(repository);
        verify(progressStore, never()).save(anyInt(), any());
    }

    @Test
    void shouldPublishDeterministicProgressAndCompleteTask() {
        AnalysisTaskEntity task = taskWithStatus("PENDING");
        when(repository.selectById(101L)).thenReturn(task);
        when(repository.claimPending(eq(101L), eq("PREPARING"), eq(20), any(LocalDateTime.class)))
            .thenReturn(1);
        when(repository.updateProcessingProgress(eq(101L), anyString(), anyInt(), any(LocalDateTime.class)))
            .thenReturn(1);
        when(repository.markSuccess(eq(101L), any(LocalDateTime.class))).thenReturn(1);

        processor.process(new AnalysisMessage(101L, 7L));

        verify(repository).updateProcessingProgress(eq(101L), eq("ANALYZING"), eq(40), any(LocalDateTime.class));
        verify(repository).updateProcessingProgress(eq(101L), eq("PROCESSING"), eq(70), any(LocalDateTime.class));
        verify(repository).updateProcessingProgress(eq(101L), eq("SAVING"), eq(90), any(LocalDateTime.class));
        verify(repository).markSuccess(eq(101L), any(LocalDateTime.class));

        ArgumentCaptor<AnalysisProgressSnapshot> progressCaptor =
            ArgumentCaptor.forClass(AnalysisProgressSnapshot.class);
        verify(progressStore, times(5)).save(eq(101L), progressCaptor.capture());
        List<AnalysisProgressSnapshot> snapshots = progressCaptor.getAllValues();
        assertThat(snapshots).extracting(AnalysisProgressSnapshot::progress)
            .containsExactly(20, 40, 70, 90, 100);
        assertThat(snapshots).extracting(AnalysisProgressSnapshot::stage)
            .containsExactly("PREPARING", "ANALYZING", "PROCESSING", "SAVING", "DONE");
        assertThat(snapshots.getLast().status()).isEqualTo("SUCCESS");
    }

    @Test
    void shouldSkipWhenAnotherConsumerAlreadyClaimedPendingTask() {
        AnalysisTaskEntity task = taskWithStatus("PENDING");
        when(repository.selectById(101L)).thenReturn(task);
        when(repository.claimPending(eq(101L), eq("PREPARING"), eq(20), any(LocalDateTime.class)))
            .thenReturn(0);

        processor.process(new AnalysisMessage(101L, 7L));

        verify(repository, never()).updateProcessingProgress(anyInt(), anyString(), anyInt(), any());
        verify(repository, never()).markSuccess(anyInt(), any());
        verify(progressStore, never()).save(anyInt(), any());
    }

    private AnalysisTaskEntity taskWithStatus(String status) {
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setId(101L);
        task.setVideoId(7L);
        task.setStatus(status);
        task.setStage(status.equals("SUCCESS") ? "DONE" : "QUEUED");
        task.setProgress(status.equals("SUCCESS") ? 100 : 0);
        return task;
    }
}
