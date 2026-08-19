package com.videoagent.analysis.service;

import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.outbox.OutboxService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class AnalysisRetryCoordinator {

    public enum RetryOutcome {
        RETRY_SCHEDULED,
        FAILED_TERMINAL,
        NO_CHANGE
    }

    private static final Logger log = LoggerFactory.getLogger(AnalysisRetryCoordinator.class);
    private static final long BACKOFF_BASE_SECONDS = 5;
    private static final long BACKOFF_CAP_SECONDS = 60;
    private static final Duration RETRY_AFTER_CAP = Duration.ofMinutes(15);

    private final AnalysisTaskRepository taskRepository;
    private final OutboxService outboxService;
    private final AnalysisReliabilityProperties properties;

    public AnalysisRetryCoordinator(
        AnalysisTaskRepository taskRepository,
        OutboxService outboxService,
        AnalysisReliabilityProperties properties
    ) {
        this.taskRepository = taskRepository;
        this.outboxService = outboxService;
        this.properties = properties;
    }

    /**
     * Handles a retryable failure of the attempt identified by
     * {@code task.processingGeneration}. The RETRY_WAITING transition and the
     * retry outbox INSERT run in the SAME transaction (MEDIUM #8): if the event
     * insert fails, the state transition rolls back and the task stays
     * PROCESSING for the current fencing token.
     */
    @Transactional
    public RetryOutcome handleRetryableFailure(
        AnalysisTaskEntity task,
        String stage,
        String errorCode,
        String errorMessage
    ) {
        return handleRetryableFailure(task, stage, errorCode, errorMessage, null);
    }

    @Transactional
    public RetryOutcome handleRetryableFailure(
        AnalysisTaskEntity task,
        String stage,
        String errorCode,
        String errorMessage,
        Duration providerRetryAfter
    ) {
        int generation = task.getProcessingGeneration() == null ? 0 : task.getProcessingGeneration();
        int attemptCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextAttemptAt = now.plus(retryDelay(attemptCount + 1, providerRetryAfter));
        int newGeneration = generation + 1;

        int updated = taskRepository.markRetryWaitingForGeneration(
            task.getId(),
            generation,
            stage,
            errorCode,
            errorMessage,
            nextAttemptAt,
            properties.maxAttempts(),
            now
        );
        if (updated == 1) {
            outboxService.enqueueRetry(task, newGeneration, nextAttemptAt);
            log.info(
                "[taskId={}][videoId={}][generation={}][stage={}] retryable failure recorded; next delivery at {}",
                task.getId(), task.getVideoId(), newGeneration, stage, nextAttemptAt
            );
            return RetryOutcome.RETRY_SCHEDULED;
        }

        int failed = taskRepository.markFailedForBudgetExhausted(
            task.getId(),
            generation,
            errorCode,
            errorMessage,
            properties.maxAttempts(),
            now
        );
        if (failed == 1) {
            log.warn(
                "[taskId={}][videoId={}][generation={}][stage={}] reached max analysis attempts ({}); marking FAILED",
                task.getId(), task.getVideoId(), generation, stage, properties.maxAttempts()
            );
            return RetryOutcome.FAILED_TERMINAL;
        }

        log.info(
            "[taskId={}][videoId={}][generation={}] task was claimed or transitioned concurrently; skipping retry scheduling",
            task.getId(), task.getVideoId(), generation
        );
        return RetryOutcome.NO_CHANGE;
    }

    public Duration backoffDuration(int nextAttempt) {
        long seconds = BACKOFF_BASE_SECONDS * (1L << Math.min(nextAttempt - 1, 16));
        return Duration.ofSeconds(Math.min(seconds, BACKOFF_CAP_SECONDS));
    }

    /** Exponential delay plus bounded random jitter; Retry-After wins when longer. */
    public Duration retryDelay(int nextAttempt, Duration providerRetryAfter) {
        Duration base = backoffDuration(nextAttempt);
        long jitterBound = Math.max(1, Math.min(2_000, base.toMillis() / 4));
        Duration withJitter = base.plusMillis(ThreadLocalRandom.current().nextLong(jitterBound));
        if (providerRetryAfter == null || providerRetryAfter.isNegative()) {
            return withJitter;
        }
        Duration boundedProviderDelay = providerRetryAfter.compareTo(RETRY_AFTER_CAP) > 0
            ? RETRY_AFTER_CAP
            : providerRetryAfter;
        return boundedProviderDelay.compareTo(withJitter) > 0 ? boundedProviderDelay : withJitter;
    }
}
