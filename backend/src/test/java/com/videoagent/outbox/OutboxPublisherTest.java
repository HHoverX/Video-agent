package com.videoagent.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.analysis.dto.AnalysisMessage;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.producer.AnalysisTaskProducer;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.analysis.service.TerminalNotifier;
import com.videoagent.outbox.entity.AnalysisOutboxEventEntity;
import com.videoagent.outbox.entity.OutboxEventStatus;
import com.videoagent.outbox.repository.AnalysisOutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

class OutboxPublisherTest {

    private final AnalysisOutboxEventRepository eventRepository = mock(AnalysisOutboxEventRepository.class);
    private final AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    private final AnalysisTaskProducer producer = mock(AnalysisTaskProducer.class);
    private final TerminalNotifier terminalNotifier = mock(TerminalNotifier.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OutboxProperties properties = new OutboxProperties(
        5000,
        Duration.ofSeconds(5),
        2.0,
        15,
        20
    );
    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(eventRepository, taskRepository, producer, properties, objectMapper, terminalNotifier);
    }

    @Test
    void shouldPublishEventAndMarkPublished() throws Exception {
        AnalysisOutboxEventEntity event = event(1L, "dispatch:101", "ANALYSIS_DISPATCH", 101L, 7L, 0);
        when(eventRepository.findDuePending(any(LocalDateTime.class), eq(20))).thenReturn(List.of(event));
        when(eventRepository.markPublished(eq(1L), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);
        when(taskRepository.selectById(101L)).thenReturn(task(101L, 7L, "PENDING"));

        publisher.publishDue();

        verify(producer).send(new AnalysisMessage(101L, 7L));
        verify(eventRepository).markPublished(eq(1L), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void shouldIncrementAttemptAndBackoffWhenPublishTemporarilyFails() throws Exception {
        AnalysisOutboxEventEntity event = event(2L, "dispatch:202", "ANALYSIS_DISPATCH", 202L, 8L, 0);
        when(eventRepository.findDuePending(any(LocalDateTime.class), eq(20))).thenReturn(List.of(event));
        when(taskRepository.selectById(202L)).thenReturn(task(202L, 8L, "PENDING"));
        doThrow(new IllegalStateException("broker down")).when(producer).send(any(AnalysisMessage.class));
        when(eventRepository.markRetry(
            eq(2L), any(LocalDateTime.class), eq("broker down"), any(LocalDateTime.class)
        )).thenReturn(1);

        publisher.publishDue();

        verify(eventRepository).markRetry(
            eq(2L), any(LocalDateTime.class), eq("broker down"), any(LocalDateTime.class)
        );
        verify(eventRepository, never()).markPublished(anyLong(), any(), any());
    }

    @Test
    void shouldExhaustEventAndFailPendingTaskWhenMaxAttemptsReached() throws Exception {
        AnalysisOutboxEventEntity event = event(3L, "dispatch:303", "ANALYSIS_DISPATCH", 303L, 9L, 15);
        when(eventRepository.findDuePending(any(LocalDateTime.class), eq(20))).thenReturn(List.of(event));
        when(eventRepository.markExhausted(eq(3L), anyString(), any(LocalDateTime.class))).thenReturn(1);
        when(taskRepository.selectById(303L)).thenReturn(task(303L, 9L, "PENDING"));
        when(taskRepository.markFailedIfNotStarted(
            eq(303L), eq("ANALYSIS_DISPATCH_EXHAUSTED"), anyString(), any(LocalDateTime.class)
        )).thenReturn(1);

        publisher.publishDue();

        verify(producer, never()).send(any(AnalysisMessage.class));
        verify(eventRepository).markExhausted(eq(3L), anyString(), any(LocalDateTime.class));
        verify(taskRepository).markFailedIfNotStarted(
            eq(303L), eq("ANALYSIS_DISPATCH_EXHAUSTED"), anyString(), any(LocalDateTime.class)
        );
        verify(terminalNotifier).failed(eq(303L), eq(9L), anyInt(), eq("ANALYSIS_DISPATCH_EXHAUSTED"), anyString());
    }

    @Test
    void shouldFailRetryWaitingTaskWhenRetryEventExhausted() throws Exception {
        AnalysisOutboxEventEntity event = event(4L, "retry:404:1", "ANALYSIS_RETRY", 404L, 10L, 15);
        when(eventRepository.findDuePending(any(LocalDateTime.class), eq(20))).thenReturn(List.of(event));
        when(eventRepository.markExhausted(eq(4L), anyString(), any(LocalDateTime.class))).thenReturn(1);
        when(taskRepository.selectById(404L)).thenReturn(task(404L, 10L, "RETRY_WAITING"));
        when(taskRepository.markFailedIfNotStarted(
            eq(404L), eq("ANALYSIS_RETRY_EXHAUSTED"), anyString(), any(LocalDateTime.class)
        )).thenReturn(1);

        publisher.publishDue();

        verify(producer, never()).send(any(AnalysisMessage.class));
        verify(eventRepository).markExhausted(eq(4L), anyString(), any(LocalDateTime.class));
        verify(taskRepository).markFailedIfNotStarted(
            eq(404L), eq("ANALYSIS_RETRY_EXHAUSTED"), anyString(), any(LocalDateTime.class)
        );
        verify(terminalNotifier).failed(eq(404L), eq(10L), anyInt(), eq("ANALYSIS_RETRY_EXHAUSTED"), anyString());
    }

    @Test
    void shouldNotFailTaskWhenExhaustedEventAlreadyClaimed() throws Exception {
        AnalysisOutboxEventEntity event = event(5L, "dispatch:505", "ANALYSIS_DISPATCH", 505L, 11L, 15);
        when(eventRepository.findDuePending(any(LocalDateTime.class), eq(20))).thenReturn(List.of(event));
        when(eventRepository.markExhausted(eq(5L), anyString(), any(LocalDateTime.class))).thenReturn(0);

        publisher.publishDue();

        verify(producer, never()).send(any(AnalysisMessage.class));
        verify(taskRepository, never()).markFailedIfNotStarted(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void shouldCancelTerminalTaskPendingEventInsteadOfDroppingIt() throws Exception {
        // MQ send succeeded but the JVM crashed before markPublished; the task
        // has since completed. The old PENDING event must leave the due set.
        AnalysisOutboxEventEntity event = event(6L, "dispatch:606", "ANALYSIS_DISPATCH", 606L, 12L, 0);
        when(eventRepository.findDuePending(any(LocalDateTime.class), eq(20))).thenReturn(List.of(event));
        when(eventRepository.markCancelled(eq(6L), anyString(), any(LocalDateTime.class))).thenReturn(1);
        when(taskRepository.selectById(606L)).thenReturn(task(606L, 12L, "SUCCESS"));

        publisher.publishDue();

        verify(producer, never()).send(any(AnalysisMessage.class));
        verify(eventRepository).markCancelled(eq(6L), anyString(), any(LocalDateTime.class));
        verify(eventRepository, never()).markPublished(anyLong(), any(), any());
    }

    @Test
    void shouldMarkInvalidUnreadablePayloadAndFailPendingDispatchTask() throws Exception {
        AnalysisOutboxEventEntity event = new AnalysisOutboxEventEntity();
        event.setId(7L);
        event.setEventKey("dispatch:707");
        event.setEventType("ANALYSIS_DISPATCH");
        event.setTaskId(707L);
        event.setVideoId(13L);
        event.setStatus(OutboxEventStatus.PENDING.name());
        event.setAttemptCount(0);
        event.setPayload("not-json{");
        when(eventRepository.findDuePending(any(LocalDateTime.class), eq(20))).thenReturn(List.of(event));
        when(eventRepository.markInvalid(eq(7L), anyString(), any(LocalDateTime.class))).thenReturn(1);
        when(taskRepository.selectById(707L)).thenReturn(task(707L, 13L, "PENDING"));
        when(taskRepository.markFailedIfNotStarted(
            eq(707L), eq("ANALYSIS_DISPATCH_EXHAUSTED"), anyString(), any(LocalDateTime.class)
        )).thenReturn(1);

        publisher.publishDue();

        verify(producer, never()).send(any(AnalysisMessage.class));
        verify(eventRepository).markInvalid(eq(7L), anyString(), any(LocalDateTime.class));
        verify(taskRepository).markFailedIfNotStarted(
            eq(707L), eq("ANALYSIS_DISPATCH_EXHAUSTED"), anyString(), any(LocalDateTime.class)
        );
    }

    private AnalysisOutboxEventEntity event(long id, String key, String type, long taskId, long videoId, int attemptCount) {
        AnalysisOutboxEventEntity event = new AnalysisOutboxEventEntity();
        event.setId(id);
        event.setEventKey(key);
        event.setEventType(type);
        event.setTaskId(taskId);
        event.setVideoId(videoId);
        event.setStatus(OutboxEventStatus.PENDING.name());
        event.setAttemptCount(attemptCount);
        event.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        try {
            event.setPayload(objectMapper.writeValueAsString(new AnalysisMessage(taskId, videoId)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        return event;
    }

    private AnalysisTaskEntity task(long id, long videoId, String status) {
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setId(id);
        task.setVideoId(videoId);
        task.setStatus(status);
        task.setProgress(10);
        return task;
    }
}
