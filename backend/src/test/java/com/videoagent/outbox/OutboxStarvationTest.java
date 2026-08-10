package com.videoagent.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.analysis.dto.AnalysisMessage;
import com.videoagent.analysis.entity.AnalysisStatus;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * HIGH #5 starvation test. If a full batch is made up of old, dead outbox
 * events (terminal-task PENDING events + unreadable INVALID payloads), they
 * must all leave PENDING (CANCELLED / INVALID / EXHAUSTED) so that a normal
 * new due event placed after them is eventually producer.send()-ed. A mock
 * repository simulates the DB by advancing event state on each markX call and
 * returning the updated rows on subsequent findDuePending queries.
 */
class OutboxStarvationTest {

    private static final int BATCH_SIZE = 20;

    private final AnalysisOutboxEventRepository eventRepository = mock(AnalysisOutboxEventRepository.class);
    private final AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    private final AnalysisTaskProducer producer = mock(AnalysisTaskProducer.class);
    private final TerminalNotifier terminalNotifier = mock(TerminalNotifier.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, AnalysisOutboxEventEntity> events = new ConcurrentHashMap<>();

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        OutboxProperties properties = new OutboxProperties(
            5000,
            Duration.ofSeconds(5),
            2.0,
            15,
            BATCH_SIZE
        );
        publisher = new OutboxPublisher(eventRepository, taskRepository, producer, properties, objectMapper, terminalNotifier);

        // In-memory DB simulation: findDuePending returns PENDING, due rows.
        when(eventRepository.findDuePending(any(LocalDateTime.class), eq(BATCH_SIZE)))
            .thenAnswer(invocation -> events.values().stream()
                .filter(e -> OutboxEventStatus.PENDING.name().equals(e.getStatus()))
                .filter(e -> !e.getNextAttemptAt().isAfter(invocation.getArgument(0, LocalDateTime.class)))
                .sorted(java.util.Comparator.comparing(AnalysisOutboxEventEntity::getNextAttemptAt)
                    .thenComparing(AnalysisOutboxEventEntity::getId))
                .limit(BATCH_SIZE)
                .collect(Collectors.toList()));
        // Conditional state transitions.
        when(eventRepository.markPublished(anyLong(), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenAnswer(invocation -> transition(invocation.getArgument(0), OutboxEventStatus.PUBLISHED));
        when(eventRepository.markCancelled(anyLong(), anyString(), any(LocalDateTime.class)))
            .thenAnswer(invocation -> transition(invocation.getArgument(0), OutboxEventStatus.CANCELLED));
        when(eventRepository.markInvalid(anyLong(), anyString(), any(LocalDateTime.class)))
            .thenAnswer(invocation -> transition(invocation.getArgument(0), OutboxEventStatus.INVALID));
        when(eventRepository.markExhausted(anyLong(), anyString(), any(LocalDateTime.class)))
            .thenAnswer(invocation -> transition(invocation.getArgument(0), OutboxEventStatus.EXHAUSTED));
        when(eventRepository.markRetry(anyLong(), any(LocalDateTime.class), anyString(), any(LocalDateTime.class)))
            .thenAnswer(invocation -> {
                AnalysisOutboxEventEntity e = events.get(invocation.getArgument(0));
                if (e == null || !OutboxEventStatus.PENDING.name().equals(e.getStatus())) {
                    return 0;
                }
                e.setAttemptCount(e.getAttemptCount() + 1);
                e.setNextAttemptAt(invocation.getArgument(1, LocalDateTime.class));
                return 1;
            });

        // Most events map to terminal tasks -> CANCELLED. The "fresh" event's
        // task (id 888/999/777) is PENDING so it should be published.
        when(taskRepository.selectById(anyLong())).thenAnswer(invocation -> {
            long taskId = invocation.getArgument(0);
            AnalysisTaskEntity t = new AnalysisTaskEntity();
            t.setId(taskId);
            t.setVideoId(1L);
            t.setStatus(isFreshTask(taskId) ? AnalysisStatus.PENDING.name() : AnalysisStatus.SUCCESS.name());
            return t;
        });
    }

    private boolean isFreshTask(long taskId) {
        // Fresh/sendable tasks are PENDING; everything else is SUCCESS
        // (terminal) and its events get CANCELLED.
        return taskId == 999L || taskId == 888L || taskId == 777L
            || (taskId >= 300L && taskId <= 319L);
    }

    @Test
    void shouldPublishFreshEventEvenWhenBatchIsFullOfDeadEvents() {
        // Fill a full batch with terminal-task PENDING events.
        for (int i = 0; i < BATCH_SIZE; i++) {
            events.put(nextId.incrementAndGet(), dispatchEvent(nextId.get(), 100L + i, true));
        }
        // Add one fresh, normal dispatch event that MUST be delivered.
        long freshEventId = nextId.incrementAndGet();
        events.put(freshEventId, dispatchEvent(freshEventId, 999L, false));

        // First round: the batch is consumed by the BATCH_SIZE terminal events.
        publisher.publishDue();

        // All terminal events must have left PENDING (CANCELLED).
        assertThat(events.values().stream()
            .filter(e -> e.getEventKey().startsWith("dispatch:100") || e.getEventKey().startsWith("dispatch:10")
                || e.getEventKey().startsWith("dispatch:11"))
            .filter(e -> OutboxEventStatus.PENDING.name().equals(e.getStatus()))
            .count()).isZero();
        long terminalCancelled = events.values().stream()
            .filter(e -> OutboxEventStatus.CANCELLED.name().equals(e.getStatus()))
            .count();
        assertThat(terminalCancelled).isEqualTo(BATCH_SIZE);

        // The fresh event was NOT in the first batch (it sorts after the dead
        // events by next_attempt_at, which are all already due at insertion).
        // Run the publisher again: now only the fresh event is due -> it is sent.
        publisher.publishDue();

        verify(producer).send(new AnalysisMessage(999L, 1L));
        assertThat(events.get(freshEventId).getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED.name());
        assertThat(events.values().stream()
            .filter(e -> OutboxEventStatus.PENDING.name().equals(e.getStatus()))
            .count()).isZero();
    }

    @Test
    void shouldClearInvalidEventsAndDeliverFreshOneAcrossRounds() {
        // One batch of dead events: mix of terminal (CANCELLED) and unreadable
        // payload (INVALID).
        for (int i = 0; i < BATCH_SIZE - 1; i++) {
            events.put(nextId.incrementAndGet(), dispatchEvent(nextId.get(), 200L + i, true));
        }
        long invalidEventId = nextId.incrementAndGet();
        events.put(invalidEventId, invalidPayloadEvent(invalidEventId, 500L));
        // Fresh normal event to be delivered.
        long freshEventId = nextId.incrementAndGet();
        events.put(freshEventId, dispatchEvent(freshEventId, 888L, false));

        // Round 1: batch consumed by dead events; the unreadable payload one is
        // INVALID, the terminal ones CANCELLED.
        publisher.publishDue();
        assertThat(events.get(invalidEventId).getStatus()).isEqualTo(OutboxEventStatus.INVALID.name());

        // Round 2: only the fresh event remains due -> delivered.
        publisher.publishDue();
        verify(producer).send(new AnalysisMessage(888L, 1L));
        assertThat(events.get(freshEventId).getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED.name());
        assertThat(events.values().stream()
            .filter(e -> OutboxEventStatus.PENDING.name().equals(e.getStatus()))
            .count()).isZero();
    }

    @Test
    void shouldDeliverFreshEventEvenWhenDeadEventsExhaustFirst() {
        // A batch of broker-down events on PENDING tasks. With the publisher's
        // maxAttempts, they retry on each round; once past budget they exhaust
        // and free the batch for the fresh event.
        for (int i = 0; i < BATCH_SIZE; i++) {
            events.put(nextId.incrementAndGet(), dispatchEvent(nextId.get(), 300L + i, true));
        }
        long freshEventId = nextId.incrementAndGet();
        events.put(freshEventId, dispatchEvent(freshEventId, 777L, true));
        // Make the dead events' tasks PENDING too (they must attempt to send and
        // fail) by registering them as non-terminal. The simplest way: treat
        // tasks 300..319 and 777 as PENDING.
        // Force every send to fail so we can observe the dead events retrying
        // then exhausting, while the fresh one only succeeds once the batch is
        // clear.
        org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
            .when(producer).send(org.mockito.ArgumentMatchers.<AnalysisMessage>any());

        // Round 1: batch of BATCH_SIZE events all fail -> attempt=1 (PENDING).
        publisher.publishDue();
        assertThat(events.values().stream()
            .filter(e -> OutboxEventStatus.PENDING.name().equals(e.getStatus()))
            .count()).isEqualTo(BATCH_SIZE + 1);

        // Put the dead events past their budget AND make them due again so the
        // next round exhausts them instead of retrying. Push the fresh event's
        // next_attempt_at into the future so it sorts AFTER the dead events and
        // stays out of this round's batch.
        LocalDateTime now = LocalDateTime.now();
        events.values().stream()
            .filter(e -> e.getTaskId() >= 300L && e.getTaskId() <= 319L)
            .forEach(e -> {
                e.setAttemptCount(15);
                e.setNextAttemptAt(now.minusSeconds(1));
            });
        events.get(freshEventId).setNextAttemptAt(now.plusSeconds(5));
        publisher.publishDue();
        long exhaustedCount = events.values().stream()
            .filter(e -> OutboxEventStatus.EXHAUSTED.name().equals(e.getStatus()))
            .count();
        assertThat(exhaustedCount).isEqualTo(BATCH_SIZE);

        // The fresh event is still PENDING (its earlier send failed). With the
        // batch now clear, the next round delivers it (send succeeds).
        org.mockito.Mockito.doNothing().when(producer).send(org.mockito.ArgumentMatchers.<AnalysisMessage>any());
        events.get(freshEventId).setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        publisher.publishDue();
        // 20 sends in round 1 (all failed) + 1 final successful send.
        verify(producer, times(BATCH_SIZE + 1)).send(any(AnalysisMessage.class));
        assertThat(events.get(freshEventId).getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED.name());
        assertThat(events.values().stream()
            .filter(e -> OutboxEventStatus.PENDING.name().equals(e.getStatus()))
            .count()).isZero();
    }

    private AnalysisOutboxEventEntity dispatchEvent(long id, long taskId, boolean terminalTask) {
        AnalysisOutboxEventEntity e = new AnalysisOutboxEventEntity();
        e.setId(id);
        e.setEventKey("dispatch:" + id);
        e.setEventType("ANALYSIS_DISPATCH");
        e.setTaskId(taskId);
        e.setVideoId(1L);
        e.setStatus(OutboxEventStatus.PENDING.name());
        e.setAttemptCount(0);
        e.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        try {
            e.setPayload(objectMapper.writeValueAsString(new AnalysisMessage(taskId, 1L)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return e;
    }

    private AnalysisOutboxEventEntity invalidPayloadEvent(long id, long taskId) {
        AnalysisOutboxEventEntity e = dispatchEvent(id, taskId, false);
        e.setPayload("not-json{");
        return e;
    }

    private int transition(long eventId, OutboxEventStatus to) {
        AnalysisOutboxEventEntity e = events.get(eventId);
        if (e == null || !OutboxEventStatus.PENDING.name().equals(e.getStatus())) {
            return 0;
        }
        e.setStatus(to.name());
        return 1;
    }
}
