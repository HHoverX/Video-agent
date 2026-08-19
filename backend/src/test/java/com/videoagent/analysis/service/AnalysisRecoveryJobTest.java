package com.videoagent.analysis.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.outbox.OutboxService;
import com.videoagent.outbox.repository.AnalysisOutboxEventRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

class AnalysisRecoveryJobTest {

    private final AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private final AnalysisOutboxEventRepository outboxEventRepository = mock(AnalysisOutboxEventRepository.class);
    private final TerminalNotifier terminalNotifier = mock(TerminalNotifier.class);
    private final AnalysisReliabilityProperties properties = new AnalysisReliabilityProperties(
        3, Duration.ofMinutes(15), Duration.ofMinutes(2), Duration.ofHours(2)
    );
    private AnalysisRecoveryJob job;

    @BeforeEach
    void setUp() {
        job = new AnalysisRecoveryJob(taskRepository, outboxService, outboxEventRepository, terminalNotifier, properties);
    }

    @Test
    void shouldReclaimOnlyStaleProcessingWithBudget() {
        AnalysisTaskEntity stale = processingTask(101L, 1, 0);
        when(taskRepository.findStaleProcessing(any(LocalDateTime.class))).thenReturn(List.of(stale));
        when(taskRepository.reclaimStaleProcessingWithBudget(
            eq(101L), eq("ANALYSIS_PROCESSING_LEASE_EXPIRED"), anyString(), any(LocalDateTime.class),
            any(LocalDateTime.class), eq(3), any(LocalDateTime.class)
        )).thenReturn(1);
        when(outboxService.enqueueRetry(eq(stale), eq(1), any(LocalDateTime.class))).thenReturn(10L);

        job.recoverStaleProcessing();

        verify(outboxService).enqueueRetry(eq(stale), eq(1), any(LocalDateTime.class));
        verify(taskRepository, never()).reclaimStaleProcessingExhausted(anyLong(), anyString(), anyString(), any(), anyInt(), any());
        verify(terminalNotifier, never()).failed(anyLong(), anyLong(), anyInt(), anyString(), anyString());
    }

    @Test
    void shouldFailStaleTaskWhenBudgetExhausted() {
        // retry_count=2, maxAttempts=3 => no budget left.
        AnalysisTaskEntity stale = processingTask(202L, 2, 1);
        when(taskRepository.findStaleProcessing(any(LocalDateTime.class))).thenReturn(List.of(stale));
        when(taskRepository.reclaimStaleProcessingWithBudget(
            eq(202L), eq("ANALYSIS_PROCESSING_LEASE_EXPIRED"), anyString(), any(LocalDateTime.class),
            any(LocalDateTime.class), eq(3), any(LocalDateTime.class)
        )).thenReturn(0);
        when(taskRepository.reclaimStaleProcessingExhausted(
            eq(202L), eq("ANALYSIS_PROCESSING_LEASE_EXPIRED"), anyString(), any(LocalDateTime.class),
            eq(3), any(LocalDateTime.class)
        )).thenReturn(1);

        job.recoverStaleProcessing();

        verify(taskRepository).reclaimStaleProcessingExhausted(
            eq(202L), eq("ANALYSIS_PROCESSING_LEASE_EXPIRED"), anyString(), any(LocalDateTime.class),
            eq(3), any(LocalDateTime.class)
        );
        verify(outboxService, never()).enqueueRetry(any(), anyInt(), any());
        verify(terminalNotifier).failed(eq(202L), eq(7L), anyInt(), eq("ANALYSIS_PROCESSING_LEASE_EXPIRED"), anyString());
    }

    @Test
    void shouldNotConsiderFreshProcessingStale() {
        when(taskRepository.findStaleProcessing(any(LocalDateTime.class))).thenReturn(List.of());

        job.recoverStaleProcessing();

        verify(taskRepository, never()).reclaimStaleProcessingWithBudget(anyLong(), anyString(), anyString(), any(), any(), anyInt(), any());
        verify(taskRepository, never()).reclaimStaleProcessingExhausted(anyLong(), anyString(), anyString(), any(), anyInt(), any());
    }

    @Test
    void shouldNotEnqueueRetryWhenConcurrentRecoveryWon() {
        AnalysisTaskEntity stale = processingTask(303L, 1, 0);
        when(taskRepository.findStaleProcessing(any(LocalDateTime.class))).thenReturn(List.of(stale));
        when(taskRepository.reclaimStaleProcessingWithBudget(
            eq(303L), anyString(), anyString(), any(LocalDateTime.class), any(LocalDateTime.class), eq(3), any(LocalDateTime.class)
        )).thenReturn(0);
        when(taskRepository.reclaimStaleProcessingExhausted(
            eq(303L), anyString(), anyString(), any(LocalDateTime.class), eq(3), any(LocalDateTime.class)
        )).thenReturn(0);

        job.recoverStaleProcessing();

        verify(outboxService, never()).enqueueRetry(any(), anyInt(), any());
        verify(terminalNotifier, never()).failed(anyLong(), anyLong(), anyInt(), anyString(), anyString());
    }

    private AnalysisTaskEntity processingTask(long id, int retryCount, int generation) {
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setId(id);
        task.setVideoId(7L);
        task.setStatus("PROCESSING");
        task.setStage("TRANSCRIBING");
        task.setRetryCount(retryCount);
        task.setProcessingGeneration(generation);
        task.setUpdatedAt(LocalDateTime.now().minusHours(1));
        return task;
    }
}
