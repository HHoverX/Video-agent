package com.videoagent.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.videoagent.agent.config.ConversationMemoryProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

class RedisConversationMemoryTest {

    private final ConversationRedisStore redisStore = mock(ConversationRedisStore.class);
    private final ConversationCompactionScheduler scheduler = mock(ConversationCompactionScheduler.class);
    private final ConversationMemoryMetrics metrics = mock(ConversationMemoryMetrics.class);
    private RedisConversationMemory memory;

    @BeforeEach
    void setUp() {
        memory = new RedisConversationMemory(redisStore, scheduler, properties(), metrics);
    }

    @Test
    void shouldLoadSummaryAndRecentTurnsWithinContextBudget() throws Exception {
        ConversationTurn turn = new ConversationTurn("q", "a");
        when(redisStore.load(1L, 7L)).thenReturn(new ConversationHistory("summary", List.of(turn)));

        ConversationHistory history = memory.load(1L, 7L, "request-1");

        assertThat(history.summary()).isEqualTo("summary");
        assertThat(history.recentTurns()).containsExactly(turn);
        verify(metrics).increment("load.success");
    }

    @Test
    void shouldReturnEmptyContextOnRedisMiss() throws Exception {
        when(redisStore.load(1L, 7L)).thenReturn(ConversationHistory.empty());

        assertThat(memory.load(1L, 7L, "request-1")).isEqualTo(ConversationHistory.empty());
        verify(metrics).increment("load.success");
    }

    @Test
    void shouldReturnEmptyContextWhenRedisLoadFails() throws Exception {
        when(redisStore.load(1L, 7L)).thenThrow(new JsonProcessingException("bad json") { });

        assertThat(memory.load(1L, 7L, "request-1")).isEqualTo(ConversationHistory.empty());
        verify(metrics).increment("load.failure");
    }

    @Test
    void shouldAppendWithoutSchedulingBelowTrigger() throws Exception {
        ConversationTurn turn = new ConversationTurn("q", "a");
        when(redisStore.append(1L, 7L, turn)).thenReturn(9L);

        memory.appendTurn(1L, 7L, turn, "request-1");

        verify(scheduler, never()).schedule(1L, 7L);
        verify(metrics).increment("append.success");
    }

    @Test
    void shouldOnlyScheduleAsynchronousCompactionAtTrigger() throws Exception {
        ConversationTurn turn = new ConversationTurn("q", "a");
        when(redisStore.append(1L, 7L, turn)).thenReturn(10L);

        memory.appendTurn(1L, 7L, turn, "request-1");

        verify(scheduler).schedule(1L, 7L);
    }

    @Test
    void shouldNotPropagateRedisAppendFailure() throws Exception {
        ConversationTurn turn = new ConversationTurn("q", "a");
        when(redisStore.append(1L, 7L, turn)).thenThrow(new IllegalStateException("redis down"));

        assertThatCode(() -> memory.appendTurn(1L, 7L, turn, "request-1"))
            .doesNotThrowAnyException();
        verify(metrics).increment("append.failure");
    }

    @Test
    void shouldNotPropagateSchedulerFailure() throws Exception {
        ConversationTurn turn = new ConversationTurn("q", "a");
        when(redisStore.append(1L, 7L, turn)).thenReturn(10L);
        org.mockito.Mockito.doThrow(new IllegalStateException("executor down"))
            .when(scheduler).schedule(1L, 7L);

        assertThatCode(() -> memory.appendTurn(1L, 7L, turn, "request-1"))
            .doesNotThrowAnyException();
        verify(metrics).increment("compact.rejected");
    }

    @Test
    void shouldKeepV2KeysIsolatedByUserAndVideo() {
        assertThat(RedisConversationMemory.recentKey(1L, 7L))
            .isNotEqualTo(RedisConversationMemory.recentKey(2L, 7L))
            .isNotEqualTo(RedisConversationMemory.recentKey(1L, 8L))
            .endsWith(":recent");
        assertThat(RedisConversationMemory.summaryKey(1L, 7L)).endsWith(":summary");
        assertThat(RedisConversationMemory.lockKey(1L, 7L)).endsWith(":compact-lock");
    }

    private ConversationMemoryProperties properties() {
        return new ConversationMemoryProperties(
            Duration.ofHours(24),
            6,
            10,
            4,
            2_000,
            6_000,
            Duration.ofMinutes(3)
        );
    }
}
