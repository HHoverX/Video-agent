package com.videoagent.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.videoagent.analysis.service.AnalysisRetryCoordinator.RetryOutcome;
import com.videoagent.outbox.OutboxService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

class AnalysisRetryCoordinatorTest {

    private final AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private final AnalysisReliabilityProperties properties = new AnalysisReliabilityProperties(
        3, Duration.ofMinutes(15), Duration.ofMinutes(2), Duration.ofHours(2)
    );
    private AnalysisRetryCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new AnalysisRetryCoordinator(taskRepository, outboxService, properties);
    }

    @Test
    void shouldMoveToRetryWaitingAndEnqueuePerGenerationEvent() {
        AnalysisTaskEntity task = task(1, 2);
        when(taskRepository.markRetryWaitingForGeneration(
            eq(101L), eq(2), eq("TRANSCRIPT_SAVED"), eq("LLM_SUMMARY_FAILED"),
            eq("provider unavailable"), any(LocalDateTime.class), eq(3), any(LocalDateTime.class)
        )).thenReturn(1);
        when(outboxService.enqueueRetry(eq(task), eq(3), any(LocalDateTime.class))).thenReturn(9L);

        RetryOutcome outcome = coordinator.handleRetryableFailure(
            task, "TRANSCRIPT_SAVED", "LLM_SUMMARY_FAILED", "provider unavailable"
        );

        assertThat(outcome).isEqualTo(RetryOutcome.RETRY_SCHEDULED);
        verify(outboxService).enqueueRetry(eq(task), eq(3), any(LocalDateTime.class));
        verify(taskRepository, never()).markFailedForBudgetExhausted(anyLong(), anyInt(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void shouldMarkFailedTerminalWhenBudgetExhausted() {
        // retry_count=2, maxAttempts=3 => next attempt 3 >= 3 => FAILED.
        AnalysisTaskEntity task = task(2, 1);
        when(taskRepository.markRetryWaitingForGeneration(
            eq(101L), eq(1), eq("SUMMARIZING"), eq("LLM_SUMMARY_FAILED"),
            eq("provider unavailable"), any(LocalDateTime.class), eq(3), any(LocalDateTime.class)
        )).thenReturn(0);
        when(taskRepository.markFailedForBudgetExhausted(
            eq(101L), eq(1), eq("LLM_SUMMARY_FAILED"), eq("provider unavailable"), eq(3), any(LocalDateTime.class)
        )).thenReturn(1);

        RetryOutcome outcome = coordinator.handleRetryableFailure(
            task, "SUMMARIZING", "LLM_SUMMARY_FAILED", "provider unavailable"
        );

        assertThat(outcome).isEqualTo(RetryOutcome.FAILED_TERMINAL);
        verify(outboxService, never()).enqueueRetry(any(), anyInt(), any());
    }

    @Test
    void shouldReturnNoChangeWhenConcurrentTransitionWon() {
        AnalysisTaskEntity task = task(1, 1);
        when(taskRepository.markRetryWaitingForGeneration(
            eq(101L), eq(1), eq("SUMMARIZING"), eq("LLM_SUMMARY_FAILED"),
            eq("provider unavailable"), any(LocalDateTime.class), eq(3), any(LocalDateTime.class)
        )).thenReturn(0);
        when(taskRepository.markFailedForBudgetExhausted(
            eq(101L), eq(1), eq("LLM_SUMMARY_FAILED"), eq("provider unavailable"), eq(3), any(LocalDateTime.class)
        )).thenReturn(0);

        RetryOutcome outcome = coordinator.handleRetryableFailure(
            task, "SUMMARIZING", "LLM_SUMMARY_FAILED", "provider unavailable"
        );

        assertThat(outcome).isEqualTo(RetryOutcome.NO_CHANGE);
    }

    @Test
    void shouldApplyBoundedBackoff() {
        assertThat(coordinator.backoffDuration(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(coordinator.backoffDuration(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(coordinator.backoffDuration(4)).isEqualTo(Duration.ofSeconds(40));
        assertThat(coordinator.backoffDuration(10)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void shouldRespectRetryAfterAheadOfExponentialBackoff() {
        Duration delay = coordinator.retryDelay(1, Duration.ofSeconds(45));

        assertThat(delay).isGreaterThanOrEqualTo(Duration.ofSeconds(45));
        assertThat(delay).isLessThanOrEqualTo(Duration.ofMinutes(15));
    }

    private AnalysisTaskEntity task(int retryCount, int generation) {
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setId(101L);
        task.setVideoId(7L);
        task.setStatus("PROCESSING");
        task.setRetryCount(retryCount);
        task.setProcessingGeneration(generation);
        return task;
    }
}
