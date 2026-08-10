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
import static org.mockito.Mockito.times;
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
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * MEDIUM #9 boundary tests: OUTBOX_MAX_ATTEMPTS must mean "real producer.send
 * attempts". An event with maxAttempts=1 must still get at least one real send
 * (a reopen/generation change must not consume budget before any send), and a
 * fresh retry generation must never inherit a previous generation's publish
 * budget.
 */
class OutboxAttemptBudgetTest {

    private final AnalysisOutboxEventRepository eventRepository = mock(AnalysisOutboxEventRepository.class);
    private final AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    private final AnalysisTaskProducer producer = mock(AnalysisTaskProducer.class);
    private final TerminalNotifier terminalNotifier = mock(TerminalNotifier.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(taskRepository.selectById(anyLong())).thenReturn(task(1L, "PENDING"));
    }

    @Test
    void shouldStillSendAtLeastOnceWhenMaxAttemptsIsOne() throws Exception {
        // attempt_count=0 with maxAttempts=1 must NOT be exhausted before a
        // real send: the event gets exactly one producer.send.
        OutboxPublisher publisher = publisherWithMaxAttempts(1);
        AnalysisOutboxEventEntity event = event(1L, "dispatch:101", 101L, 7L, 0);
        when(eventRepository.findDuePending(any(LocalDateTime.class), eq(20))).thenReturn(List.of(event));
        when(eventRepository.markPublished(eq(1L), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);

        publisher.publishDue();

        verify(producer).send(new AnalysisMessage(101L, 7L));
        verify(eventRepository).markPublished(eq(1L), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(eventRepository, never()).markExhausted(anyLong(), anyString(), any());
        verify(eventRepository, never()).markRetry(anyLong(), any(), anyString(), any());
    }

    @Test
    void shouldExhaustOnlyAfterOneRealSendWhenMaxAttemptsIsOne() throws Exception {
        // Round 1: attempt_count=0 -> real send, which fails -> markRetry.
        // Round 2: attempt_count=1 >= maxAttempts=1 -> EXHAUSTED, no more send.
        OutboxPublisher publisher = publisherWithMaxAttempts(1);

        AnalysisOutboxEventEntity event = event(2L, "dispatch:202", 202L, 8L, 0);
        when(eventRepository.findDuePending(any(LocalDateTime.class), eq(20))).thenReturn(List.of(event));
        doThrow(new IllegalStateException("broker down")).when(producer).send(any(AnalysisMessage.class));
        when(eventRepository.markRetry(eq(2L), any(LocalDateTime.class), anyString(), any(LocalDateTime.class)))
            .thenReturn(1);

        publisher.publishDue();
        verify(producer, times(1)).send(any(AnalysisMessage.class));
        verify(eventRepository).markRetry(eq(2L), any(LocalDateTime.class), anyString(), any(LocalDateTime.class));
        verify(eventRepository, never()).markExhausted(anyLong(), any(), any());

        // Round 2: event now has attempt_count=1.
        AnalysisOutboxEventEntity exhaustedEvent = event(2L, "dispatch:202", 202L, 8L, 1);
        when(eventRepository.findDuePending(any(LocalDateTime.class), eq(20))).thenReturn(List.of(exhaustedEvent));
        when(eventRepository.markExhausted(eq(2L), anyString(), any(LocalDateTime.class))).thenReturn(1);

        publisher.publishDue();
        verify(producer, times(1)).send(any(AnalysisMessage.class)); // still exactly one real send
        verify(eventRepository).markExhausted(eq(2L), anyString(), any(LocalDateTime.class));
    }

    @Test
    void shouldGrantExactlyTwoRealSendsWhenMaxAttemptsIsTwo() throws Exception {
        OutboxPublisher publisher = publisherWithMaxAttempts(2);

        AnalysisOutboxEventEntity event = event(3L, "dispatch:303", 303L, 9L, 0);
        when(eventRepository.findDuePending(any(LocalDateTime.class), eq(20))).thenReturn(List.of(event));
        doThrow(new IllegalStateException("broker down")).when(producer).send(any(AnalysisMessage.class));
        when(eventRepository.markRetry(eq(3L), any(LocalDateTime.class), anyString(), any(LocalDateTime.class)))
            .thenReturn(1);

        // Round 1: attempt=0 < 2 -> send (fails) -> attempt=1.
        publisher.publishDue();
        verify(producer, times(1)).send(any(AnalysisMessage.class));

        // Round 2: attempt=1 < 2 -> send (fails) -> attempt=2.
        when(eventRepository.findDuePending(any(LocalDateTime.class), eq(20)))
            .thenReturn(List.of(event(3L, "dispatch:303", 303L, 9L, 1)));
        publisher.publishDue();
        verify(producer, times(2)).send(any(AnalysisMessage.class));

        // Round 3: attempt=2 >= 2 -> EXHAUSTED, no further send.
        when(eventRepository.findDuePending(any(LocalDateTime.class), eq(20)))
            .thenReturn(List.of(event(3L, "dispatch:303", 303L, 9L, 2)));
        when(eventRepository.markExhausted(eq(3L), anyString(), any(LocalDateTime.class))).thenReturn(1);
        publisher.publishDue();
        verify(producer, times(2)).send(any(AnalysisMessage.class)); // still exactly two real sends
        verify(eventRepository).markExhausted(eq(3L), anyString(), any(LocalDateTime.class));
    }

    @Test
    void shouldNotInheritOldGenerationPublishBudget() throws Exception {
        // A fresh retry generation gets its own event key and starts at
        // attempt_count=0 even if the previous generation exhausted its budget.
        // Simulate: gen1 event exhausted at attempt=1 (max=1); gen2 event is
        // a brand-new row with attempt=0 and must still be sendable.
        OutboxPublisher publisher = publisherWithMaxAttempts(1);

        // gen2 event, fresh, attempt_count=0.
        AnalysisOutboxEventEntity gen2 = event(4L, "retry:404:2", 404L, 10L, 0);
        when(eventRepository.findDuePending(any(LocalDateTime.class), eq(20))).thenReturn(List.of(gen2));
        when(eventRepository.markPublished(eq(4L), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);

        publisher.publishDue();

        verify(producer).send(new AnalysisMessage(404L, 10L));
        verify(eventRepository).markPublished(eq(4L), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(eventRepository, never()).markExhausted(anyLong(), any(), any());
    }

    @Test
    void shouldStartEveryRetryGenerationWithZeroAttempts() throws Exception {
        // Verify the per-generation key space and that each enqueue issues a
        // fresh INSERT with attempt_count=0 (the mapper SQL hard-codes 0).
        OutboxService service = new OutboxService(eventRepository, objectMapper);
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setId(505L);
        task.setVideoId(11L);

        when(eventRepository.insertPendingIfAbsent(
            eq("retry:505:1"), eq("ANALYSIS_RETRY"), eq(505L), eq(11L), anyString(), any(LocalDateTime.class), any(LocalDateTime.class)
        )).thenReturn(1);
        when(eventRepository.insertPendingIfAbsent(
            eq("retry:505:2"), eq("ANALYSIS_RETRY"), eq(505L), eq(11L), anyString(), any(LocalDateTime.class), any(LocalDateTime.class)
        )).thenReturn(1);
        AnalysisOutboxEventEntity g1 = event(1L, "retry:505:1", 505L, 11L, 0);
        AnalysisOutboxEventEntity g2 = event(2L, "retry:505:2", 505L, 11L, 0);
        when(eventRepository.findByEventKey("retry:505:1")).thenReturn(g1);
        when(eventRepository.findByEventKey("retry:505:2")).thenReturn(g2);

        long first = service.enqueueRetry(task, 1, LocalDateTime.now().plusSeconds(5));
        long second = service.enqueueRetry(task, 2, LocalDateTime.now().plusSeconds(5));

        assertThat(first).isEqualTo(1L);
        assertThat(second).isEqualTo(2L);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(eventRepository, times(2)).insertPendingIfAbsent(
            keyCaptor.capture(), eq("ANALYSIS_RETRY"), eq(505L), eq(11L), anyString(), any(LocalDateTime.class), any(LocalDateTime.class)
        );
        assertThat(keyCaptor.getAllValues()).containsExactly("retry:505:1", "retry:505:2");
    }

    private OutboxPublisher publisherWithMaxAttempts(int maxAttempts) {
        OutboxProperties properties = new OutboxProperties(
            5000,
            Duration.ofSeconds(5),
            2.0,
            maxAttempts,
            20
        );
        return new OutboxPublisher(eventRepository, taskRepository, producer, properties, objectMapper, terminalNotifier);
    }

    private AnalysisOutboxEventEntity event(long id, String key, long taskId, long videoId, int attemptCount) {
        AnalysisOutboxEventEntity event = new AnalysisOutboxEventEntity();
        event.setId(id);
        event.setEventKey(key);
        event.setEventType(key.startsWith("retry:") ? "ANALYSIS_RETRY" : "ANALYSIS_DISPATCH");
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

    private AnalysisTaskEntity task(long id, String status) {
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setId(id);
        task.setVideoId(7L);
        task.setStatus(status);
        task.setProgress(10);
        return task;
    }
}
