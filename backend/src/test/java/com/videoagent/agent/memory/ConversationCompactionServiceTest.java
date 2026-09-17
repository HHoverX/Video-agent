package com.videoagent.agent.memory;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.agent.config.ConversationMemoryProperties;
import com.videoagent.agent.memory.ConversationRedisStore.CommitResult;
import com.videoagent.agent.memory.ConversationRedisStore.CompactionSnapshot;
import com.videoagent.agent.memory.summary.ConversationSummaryProvider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

class ConversationCompactionServiceTest {

    private final ConversationRedisStore redisStore = mock(ConversationRedisStore.class);
    private final ConversationSummaryProvider summaryProvider = mock(ConversationSummaryProvider.class);
    private final ConversationMemoryMetrics metrics = mock(ConversationMemoryMetrics.class);
    private ConversationCompactionService service;

    @BeforeEach
    void setUp() {
        service = new ConversationCompactionService(redisStore, summaryProvider, properties(), metrics);
    }

    @Test
    void shouldSkipWhenAnotherCompactorOwnsTheLock() throws Exception {
        when(redisStore.tryAcquireLock(org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.eq(7L), anyString())).thenReturn(false);

        service.compact(1L, 7L);

        verify(redisStore, never()).snapshot(1L, 7L);
        verify(metrics).increment("compact.skipped");
    }

    @Test
    void shouldSkipWhenCurrentStateNoLongerNeedsCompaction() throws Exception {
        when(redisStore.tryAcquireLock(org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.eq(7L), anyString())).thenReturn(true);
        when(redisStore.snapshot(1L, 7L)).thenReturn(Optional.empty());

        service.compact(1L, 7L);

        verify(summaryProvider, never()).summarize(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.anyInt());
        verify(redisStore).releaseLock(org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.eq(7L), anyString());
    }

    @Test
    void shouldGenerateAndCommitSummaryThenReleaseOwnedLock() throws Exception {
        CompactionSnapshot snapshot = snapshot();
        when(redisStore.tryAcquireLock(org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.eq(7L), anyString())).thenReturn(true);
        when(redisStore.snapshot(1L, 7L)).thenReturn(Optional.of(snapshot));
        when(summaryProvider.summarize("old", snapshot.turns(), 2_000)).thenReturn("new summary");
        when(redisStore.commit(org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.eq(7L), anyString(),
            org.mockito.ArgumentMatchers.eq(snapshot), org.mockito.ArgumentMatchers.eq("new summary")))
            .thenReturn(CommitResult.SUCCESS);

        service.compact(1L, 7L);

        verify(metrics).increment("compact.success");
        verify(redisStore).releaseLock(org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.eq(7L), anyString());
    }

    @Test
    void shouldPreserveSnapshotWhenSummaryProviderFails() throws Exception {
        CompactionSnapshot snapshot = snapshot();
        when(redisStore.tryAcquireLock(org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.eq(7L), anyString())).thenReturn(true);
        when(redisStore.snapshot(1L, 7L)).thenReturn(Optional.of(snapshot));
        when(summaryProvider.summarize("old", snapshot.turns(), 2_000))
            .thenThrow(new IllegalStateException("llm timeout"));

        assertThatCode(() -> service.compact(1L, 7L)).doesNotThrowAnyException();

        verify(redisStore, never()).commit(
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
            anyString(), org.mockito.ArgumentMatchers.any(), anyString());
        verify(metrics).increment("compact.failure");
    }

    @Test
    void shouldPreserveSnapshotWhenRedisCommitFails() throws Exception {
        CompactionSnapshot snapshot = snapshot();
        when(redisStore.tryAcquireLock(org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.eq(7L), anyString())).thenReturn(true);
        when(redisStore.snapshot(1L, 7L)).thenReturn(Optional.of(snapshot));
        when(summaryProvider.summarize("old", snapshot.turns(), 2_000)).thenReturn("new summary");
        when(redisStore.commit(org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.eq(7L), anyString(),
            org.mockito.ArgumentMatchers.eq(snapshot), org.mockito.ArgumentMatchers.eq("new summary")))
            .thenThrow(new IllegalStateException("redis unavailable"));

        assertThatCode(() -> service.compact(1L, 7L)).doesNotThrowAnyException();

        verify(metrics).increment("compact.failure");
        verify(redisStore).releaseLock(org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.eq(7L), anyString());
    }

    @Test
    void shouldTreatLostLockOrChangedPrefixAsConflict() throws Exception {
        CompactionSnapshot snapshot = snapshot();
        when(redisStore.tryAcquireLock(org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.eq(7L), anyString())).thenReturn(true);
        when(redisStore.snapshot(1L, 7L)).thenReturn(Optional.of(snapshot));
        when(summaryProvider.summarize("old", snapshot.turns(), 2_000)).thenReturn("new summary");
        when(redisStore.commit(org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.eq(7L), anyString(),
            org.mockito.ArgumentMatchers.eq(snapshot), org.mockito.ArgumentMatchers.eq("new summary")))
            .thenReturn(CommitResult.LOCK_LOST);

        service.compact(1L, 7L);

        verify(metrics).increment("compact.conflict");
    }

    @Test
    void shouldRejectEmptyOrOversizedSummaryWithoutCommit() throws Exception {
        CompactionSnapshot snapshot = snapshot();
        when(redisStore.tryAcquireLock(org.mockito.ArgumentMatchers.eq(1L),
            org.mockito.ArgumentMatchers.eq(7L), anyString())).thenReturn(true);
        when(redisStore.snapshot(1L, 7L)).thenReturn(Optional.of(snapshot));
        when(summaryProvider.summarize("old", snapshot.turns(), 2_000)).thenReturn(" ");

        service.compact(1L, 7L);

        verify(redisStore, never()).commit(
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
            anyString(), org.mockito.ArgumentMatchers.any(), anyString());
        verify(metrics).increment("compact.failure");
    }

    private CompactionSnapshot snapshot() {
        ConversationTurn turn = new ConversationTurn("q1", "a1");
        return new CompactionSnapshot("old", List.of(turn), List.of("encoded-turn"));
    }

    private ConversationMemoryProperties properties() {
        return new ConversationMemoryProperties(
            Duration.ofHours(24), 6, 10, 4, 2_000, 6_000, Duration.ofMinutes(3));
    }
}
