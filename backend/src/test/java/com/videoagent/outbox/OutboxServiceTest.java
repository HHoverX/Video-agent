package com.videoagent.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.outbox.entity.AnalysisOutboxEventEntity;
import com.videoagent.outbox.entity.OutboxEventStatus;
import com.videoagent.outbox.repository.AnalysisOutboxEventRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

class OutboxServiceTest {

    private final AnalysisOutboxEventRepository eventRepository = mock(AnalysisOutboxEventRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private OutboxService service;

    @BeforeEach
    void setUp() {
        service = new OutboxService(eventRepository, objectMapper);
    }

    @Test
    void shouldEnqueueDispatchEventWithIdempotentKey() {
        AnalysisTaskEntity task = task(101L, 7L);
        when(eventRepository.insertPendingIfAbsent(
            eq("dispatch:101"), eq("ANALYSIS_DISPATCH"), eq(101L), eq(7L),
            any(String.class), any(LocalDateTime.class), any(LocalDateTime.class)
        )).thenReturn(1);
        AnalysisOutboxEventEntity existing = event(999L);
        when(eventRepository.findByEventKey("dispatch:101")).thenReturn(existing);

        long eventId = service.enqueueDispatch(task);

        assertThat(eventId).isEqualTo(999L);
        verify(eventRepository).insertPendingIfAbsent(
            eq("dispatch:101"), eq("ANALYSIS_DISPATCH"), eq(101L), eq(7L),
            any(String.class), any(LocalDateTime.class), any(LocalDateTime.class)
        );
    }

    @Test
    void shouldUsePerGenerationRetryKeys() {
        AnalysisTaskEntity task = task(202L, 8L);
        when(eventRepository.insertPendingIfAbsent(
            eq("retry:202:1"), eq("ANALYSIS_RETRY"), eq(202L), eq(8L),
            any(String.class), any(LocalDateTime.class), any(LocalDateTime.class)
        )).thenReturn(1);
        AnalysisOutboxEventEntity existing = event(998L);
        when(eventRepository.findByEventKey("retry:202:1")).thenReturn(existing);

        long eventId = service.enqueueRetry(task, 1, LocalDateTime.now().plusSeconds(5));

        assertThat(eventId).isEqualTo(998L);
        verify(eventRepository).insertPendingIfAbsent(
            eq("retry:202:1"), eq("ANALYSIS_RETRY"), eq(202L), eq(8L),
            any(String.class), any(LocalDateTime.class), any(LocalDateTime.class)
        );
    }

    @Test
    void shouldCreateDistinctEventPerRetryGeneration() {
        AnalysisTaskEntity task = task(303L, 9L);
        when(eventRepository.findByEventKey("retry:303:1")).thenReturn(null);
        when(eventRepository.findByEventKey("retry:303:2")).thenReturn(null);
        when(eventRepository.insertPendingIfAbsent(
            any(), any(), eq(303L), eq(9L), any(String.class), any(LocalDateTime.class), any(LocalDateTime.class)
        )).thenReturn(1);
        AnalysisOutboxEventEntity g1 = event(1L);
        when(eventRepository.findByEventKey("retry:303:1")).thenReturn(g1);
        AnalysisOutboxEventEntity g2 = event(2L);
        when(eventRepository.findByEventKey("retry:303:2")).thenReturn(g2);

        long first = service.enqueueRetry(task, 1, LocalDateTime.now().plusSeconds(5));
        long second = service.enqueueRetry(task, 2, LocalDateTime.now().plusSeconds(5));

        assertThat(first).isEqualTo(1L);
        assertThat(second).isEqualTo(2L);
    }

    @Test
    void shouldReuseExistingEventForIdempotentKey() {
        AnalysisTaskEntity task = task(404L, 10L);
        when(eventRepository.insertPendingIfAbsent(
            eq("dispatch:404"), eq("ANALYSIS_DISPATCH"), eq(404L), eq(10L),
            any(String.class), any(LocalDateTime.class), any(LocalDateTime.class)
        )).thenReturn(0);
        AnalysisOutboxEventEntity existing = event(997L);
        when(eventRepository.findByEventKey("dispatch:404")).thenReturn(existing);

        long eventId = service.enqueueDispatch(task);

        assertThat(eventId).isEqualTo(997L);
    }

    private AnalysisTaskEntity task(long id, long videoId) {
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setId(id);
        task.setVideoId(videoId);
        task.setStatus("PENDING");
        return task;
    }

    private AnalysisOutboxEventEntity event(long id) {
        AnalysisOutboxEventEntity event = new AnalysisOutboxEventEntity();
        event.setId(id);
        event.setEventKey("key:" + id);
        event.setTaskId(1L);
        event.setStatus(OutboxEventStatus.PENDING.name());
        event.setAttemptCount(0);
        return event;
    }
}
