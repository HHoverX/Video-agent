package com.videoagent.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.analysis.dto.AnalysisMessage;
import com.videoagent.analysis.entity.AnalysisStatus;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.producer.AnalysisTaskProducer;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.analysis.service.TerminalNotifier;
import com.videoagent.outbox.entity.AnalysisOutboxEventEntity;
import com.videoagent.outbox.entity.OutboxEventType;
import com.videoagent.outbox.repository.AnalysisOutboxEventRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final AnalysisOutboxEventRepository eventRepository;
    private final AnalysisTaskRepository taskRepository;
    private final AnalysisTaskProducer producer;
    private final OutboxProperties properties;
    private final ObjectMapper objectMapper;
    private final TerminalNotifier terminalNotifier;

    public OutboxPublisher(
        AnalysisOutboxEventRepository eventRepository,
        AnalysisTaskRepository taskRepository,
        AnalysisTaskProducer producer,
        OutboxProperties properties,
        ObjectMapper objectMapper,
        TerminalNotifier terminalNotifier
    ) {
        this.eventRepository = eventRepository;
        this.taskRepository = taskRepository;
        this.producer = producer;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.terminalNotifier = terminalNotifier;
    }

    @Scheduled(fixedDelayString = "${videoagent.outbox.publish-interval-ms:5000}")
    public void publishDue() {
        List<AnalysisOutboxEventEntity> due;
        try {
            due = eventRepository.findDuePending(LocalDateTime.now(), properties.batchSize());
        } catch (RuntimeException exception) {
            log.warn("[outbox] due event query failed; skipping this publishing round", exception);
            return;
        }
        for (AnalysisOutboxEventEntity event : due) {
            publishOne(event);
        }
    }

    private void publishOne(AnalysisOutboxEventEntity event) {
        long eventId = event.getId();
        int attempt = event.getAttemptCount() == null ? 0 : event.getAttemptCount();
        long taskId = event.getTaskId();
        long videoId = event.getVideoId();
        LocalDateTime now = LocalDateTime.now();

        if (attempt >= properties.maxAttempts()) {
            exhaust(event, "RocketMQ publish attempts exhausted");
            return;
        }

        AnalysisMessage message;
        try {
            message = objectMapper.readValue(event.getPayload(), AnalysisMessage.class);
        } catch (JsonProcessingException exception) {
            log.error(
                "[eventId={}][eventKey={}][eventType={}][taskId={}][videoId={}][publishAttempt={}] outbox payload is unreadable; marking INVALID",
                eventId, event.getEventKey(), event.getEventType(), taskId, videoId, attempt, exception
            );
            invalidate(event, "unreadable payload", taskId, videoId);
            return;
        }

        // Terminal / missing task: this event is no longer needed. Mark it
        // CANCELLED so it leaves the due set instead of starving later events.
        AnalysisTaskEntity task = taskRepository.selectById(taskId);
        if (task == null) {
            cancel(event, "task no longer exists");
            return;
        }
        if (isTerminal(task.getStatus())) {
            cancel(event, "task already terminal: " + task.getStatus());
            return;
        }

        try {
            producer.send(message);
            markPublished(event, now);
        } catch (RuntimeException exception) {
            markRetryableFailure(event, attempt, exception);
        }
    }

    private void exhaust(AnalysisOutboxEventEntity event, String reason) {
        LocalDateTime now = LocalDateTime.now();
        String safeError = safeError(reason);
        int updated = eventRepository.markExhausted(event.getId(), safeError, now);
        if (updated != 1) {
            log.info(
                "[eventId={}][taskId={}][videoId={}] outbox event was claimed by another publisher; skipping exhaustion recording",
                event.getId(), event.getTaskId(), event.getVideoId()
            );
            return;
        }
        log.error(
            "[eventId={}][eventKey={}][eventType={}][taskId={}][videoId={}][publishAttempt={}] outbox event exhausted after {} attempts",
            event.getId(), event.getEventKey(), event.getEventType(), event.getTaskId(),
            event.getVideoId(), event.getAttemptCount(), properties.maxAttempts()
        );
        failUnstartedTaskForExhaustion(event, now);
    }

    private void failUnstartedTaskForExhaustion(AnalysisOutboxEventEntity event, LocalDateTime now) {
        AnalysisTaskEntity task = taskRepository.selectById(event.getTaskId());
        if (task == null) {
            return;
        }
        int progress = task.getProgress() == null ? 0 : task.getProgress();
        if (OutboxEventType.ANALYSIS_DISPATCH.name().equals(event.getEventType())) {
            if (AnalysisStatus.PENDING.name().equals(task.getStatus())) {
                int failed = taskRepository.markFailedIfNotStarted(
                    task.getId(),
                    "ANALYSIS_DISPATCH_EXHAUSTED",
                    "分析任务投递重试耗尽，RocketMQ 一直无法接收消息",
                    now
                );
                if (failed == 1) {
                    terminalNotifier.failed(task.getId(), task.getVideoId(), progress, "ANALYSIS_DISPATCH_EXHAUSTED",
                        "分析任务投递重试耗尽，RocketMQ 一直无法接收消息");
                    log.warn("[taskId={}][videoId={}][stage=FAILED] task marked FAILED because initial dispatch was exhausted",
                        task.getId(), task.getVideoId());
                }
            }
        } else if (OutboxEventType.ANALYSIS_RETRY.name().equals(event.getEventType())) {
            if (AnalysisStatus.RETRY_WAITING.name().equals(task.getStatus())) {
                int failed = taskRepository.markFailedIfNotStarted(
                    task.getId(),
                    "ANALYSIS_RETRY_EXHAUSTED",
                    "分析任务重试投递耗尽，RocketMQ 一直无法接收重试消息",
                    now
                );
                if (failed == 1) {
                    terminalNotifier.failed(task.getId(), task.getVideoId(), progress, "ANALYSIS_RETRY_EXHAUSTED",
                        "分析任务重试投递耗尽，RocketMQ 一直无法接收重试消息");
                    log.warn("[taskId={}][videoId={}][stage=FAILED] task marked FAILED because retry dispatch was exhausted",
                        task.getId(), task.getVideoId());
                }
            }
        }
    }

    private void cancel(AnalysisOutboxEventEntity event, String reason) {
        int updated = eventRepository.markCancelled(event.getId(), safeError(reason), LocalDateTime.now());
        if (updated == 1) {
            log.info(
                "[eventId={}][eventKey={}][eventType={}][taskId={}][videoId={}] outbox event cancelled: {}",
                event.getId(), event.getEventKey(), event.getEventType(), event.getTaskId(),
                event.getVideoId(), reason
            );
        }
    }

    private void invalidate(AnalysisOutboxEventEntity event, String reason, long taskId, long videoId) {
        int updated = eventRepository.markInvalid(event.getId(), safeError(reason), LocalDateTime.now());
        if (updated == 1) {
            log.warn(
                "[eventId={}][eventKey={}][eventType={}][taskId={}][videoId={}] outbox event marked INVALID: {}",
                event.getId(), event.getEventKey(), event.getEventType(), taskId, videoId, reason
            );
            // A dispatch event that can never be delivered should not leave a
            // PENDING task stranded forever.
            AnalysisTaskEntity task = taskRepository.selectById(taskId);
            if (task != null && AnalysisStatus.PENDING.name().equals(task.getStatus())
                && OutboxEventType.ANALYSIS_DISPATCH.name().equals(event.getEventType())) {
                int failed = taskRepository.markFailedIfNotStarted(
                    taskId, "ANALYSIS_DISPATCH_EXHAUSTED", "分析任务投递事件无法解析", LocalDateTime.now()
                );
                if (failed == 1) {
                    int progress = task.getProgress() == null ? 0 : task.getProgress();
                    terminalNotifier.failed(taskId, videoId, progress, "ANALYSIS_DISPATCH_EXHAUSTED",
                        "分析任务投递事件无法解析");
                }
            }
        }
    }

    private void markPublished(AnalysisOutboxEventEntity event, LocalDateTime now) {
        int updated = eventRepository.markPublished(event.getId(), now, now);
        if (updated == 1) {
            log.info(
                "[eventId={}][eventKey={}][eventType={}][taskId={}][videoId={}][publishAttempt={}] outbox event published to RocketMQ",
                event.getId(), event.getEventKey(), event.getEventType(), event.getTaskId(),
                event.getVideoId(), event.getAttemptCount() == null ? 0 : event.getAttemptCount()
            );
        } else {
            log.info(
                "[eventId={}][taskId={}][videoId={}] outbox event was claimed and published by another publisher",
                event.getId(), event.getTaskId(), event.getVideoId()
            );
        }
    }

    private void markRetryableFailure(AnalysisOutboxEventEntity event, int attempt, RuntimeException exception) {
        LocalDateTime now = LocalDateTime.now();
        int nextAttempt = attempt + 1;
        LocalDateTime nextAttemptAt = backoffDeadline(nextAttempt, now);
        String safeError = safeError(exception.getMessage());

        int updated = eventRepository.markRetry(
            event.getId(),
            nextAttemptAt,
            safeError,
            now
        );
        if (updated != 1) {
            log.info(
                "[eventId={}][taskId={}][videoId={}] outbox event was claimed by another publisher; skipping failure recording",
                event.getId(), event.getTaskId(), event.getVideoId()
            );
            return;
        }
        log.warn(
            "[eventId={}][eventKey={}][eventType={}][taskId={}][videoId={}][publishAttempt={}] RocketMQ publish failed; scheduling next attempt {} at {}: {}",
            event.getId(), event.getEventKey(), event.getEventType(), event.getTaskId(),
            event.getVideoId(), attempt, nextAttempt, nextAttemptAt, safeError
        );
    }

    private LocalDateTime backoffDeadline(int nextAttempt, LocalDateTime now) {
        double delayMillis = properties.initialBackoff().toMillis()
            * Math.pow(properties.backoffMultiplier(), nextAttempt - 1);
        long boundedMillis = (long) Math.min(delayMillis, 60L * 60 * 1000);
        return now.plusNanos(boundedMillis * 1_000_000L);
    }

    private boolean isTerminal(String status) {
        return AnalysisStatus.SUCCESS.name().equals(status)
            || AnalysisStatus.FAILED.name().equals(status);
    }

    private String safeError(String message) {
        if (message == null || message.isBlank()) {
            return "outbox event";
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
