package com.videoagent.analysis.service;

import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.outbox.OutboxService;
import com.videoagent.outbox.entity.AnalysisOutboxEventEntity;
import com.videoagent.outbox.repository.AnalysisOutboxEventRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class AnalysisRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRecoveryJob.class);
    private static final String STALE_RECOVERY_CODE = "ANALYSIS_PROCESSING_LEASE_EXPIRED";
    private static final long RECOVERY_DELAY_SECONDS = 10;
    private static final long REARM_CUTOFF_SECONDS = 10;

    private final AnalysisTaskRepository taskRepository;
    private final OutboxService outboxService;
    private final AnalysisOutboxEventRepository outboxEventRepository;
    private final TerminalNotifier terminalNotifier;
    private final AnalysisReliabilityProperties properties;

    public AnalysisRecoveryJob(
        AnalysisTaskRepository taskRepository,
        OutboxService outboxService,
        AnalysisOutboxEventRepository outboxEventRepository,
        TerminalNotifier terminalNotifier,
        AnalysisReliabilityProperties properties
    ) {
        this.taskRepository = taskRepository;
        this.outboxService = outboxService;
        this.outboxEventRepository = outboxEventRepository;
        this.terminalNotifier = terminalNotifier;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${videoagent.analysis.recovery-interval-ms:30000}")
    public void recoverStaleProcessingTasks() {
        recoverStaleProcessing();
        rearmRetryWaiting();
    }

    void recoverStaleProcessing() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minus(properties.processingLease());
        List<AnalysisTaskEntity> staleTasks = taskRepository.findStaleProcessing(staleBefore);
        for (AnalysisTaskEntity task : staleTasks) {
            int generation = task.getProcessingGeneration() == null ? 0 : task.getProcessingGeneration();
            LocalDateTime nextAttemptAt = now.plusSeconds(RECOVERY_DELAY_SECONDS);

            // HIGH #3: apply the retry budget atomically inside the conditional
            // UPDATE. Only a task with remaining budget moves to RETRY_WAITING;
            // a task that already used its attempts moves to FAILED.
            int withBudget = taskRepository.reclaimStaleProcessingWithBudget(
                task.getId(),
                STALE_RECOVERY_CODE,
                "任务处理超时，已由恢复任务回收",
                nextAttemptAt,
                staleBefore,
                properties.maxAttempts(),
                now
            );
            if (withBudget == 1) {
                outboxService.enqueueRetry(task, generation + 1, nextAttemptAt);
                log.warn(
                    "[taskId={}][videoId={}][oldGeneration={}][newGeneration={}][staleDuration={}] reclaimed stale PROCESSING task to RETRY_WAITING",
                    task.getId(), task.getVideoId(), generation, generation + 1, properties.processingLease()
                );
                continue;
            }

            int exhausted = taskRepository.reclaimStaleProcessingExhausted(
                task.getId(),
                STALE_RECOVERY_CODE,
                "任务处理超时且重试次数已达上限",
                staleBefore,
                properties.maxAttempts(),
                now
            );
            if (exhausted == 1) {
                int progress = task.getProgress() == null ? 0 : task.getProgress();
                terminalNotifier.failed(task.getId(), task.getVideoId(), progress, STALE_RECOVERY_CODE,
                    "任务处理超时且重试次数已达上限");
                log.warn(
                    "[taskId={}][videoId={}][oldGeneration={}][newGeneration={}][staleDuration={}] stale PROCESSING task reached max attempts; FAILED",
                    task.getId(), task.getVideoId(), generation, generation + 1, properties.processingLease()
                );
            }
        }
    }

    /**
     * Ensures every RETRY_WAITING task has a deliverable retry event for its
     * current generation. Normally the coordinator already wrote that event in
     * the same transaction as the RETRY_WAITING transition; this pass only
     * covers the rare crash between that commit and the publisher, and reopens
     * an already-PUBLISHED event whose MQ message was lost. Reopening never
     * consumes a publish attempt (MEDIUM #9).
     */
    void rearmRetryWaiting() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusSeconds(REARM_CUTOFF_SECONDS);
        List<AnalysisTaskEntity> due = taskRepository.findDueRetryWaiting(cutoff);
        for (AnalysisTaskEntity task : due) {
            int generation = task.getProcessingGeneration() == null ? 0 : task.getProcessingGeneration();
            String retryKey = OutboxService.retryKey(task.getId(), generation);
            AnalysisOutboxEventEntity existing = outboxEventRepository.findByEventKey(retryKey);
            if (existing == null) {
                outboxService.enqueueRetry(task, generation, now);
                log.info(
                    "[taskId={}][videoId={}][attempt={}][generation={}][stage=RETRY_REARM] re-armed retry waiting task with a new retry outbox event",
                    task.getId(), task.getVideoId(), task.getRetryCount(), generation
                );
            } else if (OutboxService.isPublished(existing)) {
                int reopened = outboxEventRepository.reopenPublished(existing.getId(), now, now);
                if (reopened == 1) {
                    log.info(
                        "[eventId={}][taskId={}][videoId={}][generation={}][stage=RETRY_REARM] reopened published retry event for re-delivery",
                        existing.getId(), task.getId(), task.getVideoId(), generation
                    );
                }
            } else if (OutboxService.isExhausted(existing)) {
                int failed = taskRepository.markFailedIfNotStarted(
                    task.getId(),
                    "ANALYSIS_RETRY_EXHAUSTED",
                    "分析任务重试投递耗尽，RocketMQ 一直无法接收重试消息",
                    now
                );
                if (failed == 1) {
                    int progress = task.getProgress() == null ? 0 : task.getProgress();
                    terminalNotifier.failed(task.getId(), task.getVideoId(), progress, "ANALYSIS_RETRY_EXHAUSTED",
                        "分析任务重试投递耗尽，RocketMQ 一直无法接收重试消息");
                }
            }
        }
    }
}
