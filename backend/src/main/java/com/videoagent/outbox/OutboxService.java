package com.videoagent.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.analysis.dto.AnalysisMessage;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.outbox.entity.AnalysisOutboxEventEntity;
import com.videoagent.outbox.entity.OutboxEventStatus;
import com.videoagent.outbox.entity.OutboxEventType;
import com.videoagent.outbox.repository.AnalysisOutboxEventRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final AnalysisOutboxEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(
        AnalysisOutboxEventRepository eventRepository,
        ObjectMapper objectMapper
    ) {
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Enqueues the initial dispatch event. This is called from the same
     * transaction boundary as createPending() (see AnalysisCommandService), so
     * task + dispatch event commit or roll back together.
     */
    @Transactional
    public long enqueueDispatch(AnalysisTaskEntity task) {
        return enqueue(
            dispatchKey(task.getId()),
            OutboxEventType.ANALYSIS_DISPATCH,
            task.getId(),
            task.getVideoId(),
            new AnalysisMessage(task.getId(), task.getVideoId()),
            LocalDateTime.now()
        );
    }

    /**
     * Enqueues a retry event. Each analysis retry generation uses a distinct
     * event key (retry:{taskId}:{generation}) so the attempt budget of one
     * event is never polluted by a previous generation, and a reopened event
     * never consumes a publish attempt it has not performed.
     */
    @Transactional
    public long enqueueRetry(AnalysisTaskEntity task, int generation, LocalDateTime nextAttemptAt) {
        return enqueue(
            retryKey(task.getId(), generation),
            OutboxEventType.ANALYSIS_RETRY,
            task.getId(),
            task.getVideoId(),
            new AnalysisMessage(task.getId(), task.getVideoId()),
            nextAttemptAt
        );
    }

    public static String dispatchKey(long taskId) {
        return "dispatch:" + taskId;
    }

    public static String retryKey(long taskId, int generation) {
        return "retry:" + taskId + ":" + generation;
    }

    private long enqueue(
        String eventKey,
        OutboxEventType eventType,
        long taskId,
        long videoId,
        AnalysisMessage message,
        LocalDateTime nextAttemptAt
    ) {
        LocalDateTime now = LocalDateTime.now();
        int inserted = eventRepository.insertPendingIfAbsent(
            eventKey,
            eventType.name(),
            taskId,
            videoId,
            serialize(message),
            nextAttemptAt,
            now
        );
        AnalysisOutboxEventEntity existing = eventRepository.findByEventKey(eventKey);
        if (existing == null) {
            throw new IllegalStateException("Outbox event could not be persisted for taskId=" + taskId);
        }
        log.info(
            "[eventId={}][eventKey={}][eventType={}][taskId={}][videoId={}][publishAttempt={}] outbox event enqueued (inserted={})",
            existing.getId(), eventKey, eventType, taskId, videoId, existing.getAttemptCount(), inserted
        );
        return existing.getId();
    }

    private String serialize(AnalysisMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Analysis message serialization failed", exception);
        }
    }

    public static boolean isPublished(AnalysisOutboxEventEntity event) {
        return OutboxEventStatus.PUBLISHED.name().equals(event.getStatus());
    }

    public static boolean isExhausted(AnalysisOutboxEventEntity event) {
        return OutboxEventStatus.EXHAUSTED.name().equals(event.getStatus());
    }
}
