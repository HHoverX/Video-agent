package com.videoagent.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.videoagent.agent.config.ConversationMemoryProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

class PersistentConversationMemoryTest {

    private final RedisConversationMemory redisMemory = mock(RedisConversationMemory.class);
    private final ConversationTurnStore turnStore = mock(ConversationTurnStore.class);
    private final ConversationMemoryProperties properties =
        new ConversationMemoryProperties(Duration.ofHours(24), 2, 6_000);
    private PersistentConversationMemory memory;

    @BeforeEach
    void setUp() {
        memory = new PersistentConversationMemory(redisMemory, turnStore, properties);
    }

    @Test
    void shouldReturnRedisHitWithoutReadingMysql() {
        ConversationHistory cached = history("q1", "q2");
        when(redisMemory.load(1L, 7L)).thenReturn(Optional.of(cached));

        assertThat(memory.load(1L, 7L)).isEqualTo(cached);
        verifyNoInteractions(turnStore);
    }

    @Test
    void shouldLoadOnlyRecentTurnsFromMysqlAndRefillRedisOnMiss() {
        ConversationHistory recent = history("q2", "q3");
        when(redisMemory.load(1L, 7L)).thenReturn(Optional.empty());
        when(turnStore.loadRecent(1L, 7L, 2)).thenReturn(recent);

        assertThat(memory.load(1L, 7L)).isEqualTo(recent);

        verify(turnStore).loadRecent(1L, 7L, 2);
        verify(redisMemory).replace(1L, 7L, recent);
    }

    @Test
    void shouldFallbackToMysqlWhenRedisIsUnavailable() {
        ConversationHistory recent = history("q1");
        when(redisMemory.load(1L, 7L))
            .thenThrow(new DataAccessResourceFailureException("redis unavailable"));
        when(turnStore.loadRecent(1L, 7L, 2)).thenReturn(recent);

        assertThat(memory.load(1L, 7L)).isEqualTo(recent);
    }

    @Test
    void shouldReturnMysqlHistoryWhenRedisRefillFails() {
        ConversationHistory recent = history("q1");
        when(redisMemory.load(1L, 7L)).thenReturn(Optional.empty());
        when(turnStore.loadRecent(1L, 7L, 2)).thenReturn(recent);
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("redis unavailable"))
            .when(redisMemory).replace(1L, 7L, recent);

        assertThat(memory.load(1L, 7L)).isEqualTo(recent);
    }

    @Test
    void shouldCommitMysqlBeforeRefreshingRedisRecentTurns() {
        ConversationTurn turn = new ConversationTurn("q3", "a3");
        ConversationHistory recent = history("q2", "q3");
        when(turnStore.loadRecent(1L, 7L, 2)).thenReturn(recent);

        memory.appendTurn(1L, 7L, turn);

        var order = inOrder(turnStore, redisMemory);
        order.verify(turnStore).append(1L, 7L, turn);
        order.verify(turnStore).loadRecent(1L, 7L, 2);
        order.verify(redisMemory).replace(1L, 7L, recent);
    }

    @Test
    void shouldKeepMysqlTurnWhenRedisWriteFails() {
        ConversationTurn turn = new ConversationTurn("q", "a");
        ConversationHistory recent = new ConversationHistory(List.of(turn));
        when(turnStore.loadRecent(1L, 7L, 2)).thenReturn(recent);
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("redis unavailable"))
            .when(redisMemory).replace(1L, 7L, recent);

        assertThatCode(() -> memory.appendTurn(1L, 7L, turn)).doesNotThrowAnyException();
        verify(turnStore).append(1L, 7L, turn);
    }

    @Test
    void shouldNotCreateRedisOnlyHistoryWhenMysqlWriteFails() {
        ConversationTurn turn = new ConversationTurn("q", "a");
        DataAccessResourceFailureException failure =
            new DataAccessResourceFailureException("mysql unavailable");
        org.mockito.Mockito.doThrow(failure).when(turnStore).append(1L, 7L, turn);

        assertThatThrownBy(() -> memory.appendTurn(1L, 7L, turn)).isSameAs(failure);

        verify(redisMemory, never()).replace(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void shouldPropagateMysqlFallbackFailure() {
        when(redisMemory.load(1L, 7L)).thenReturn(Optional.empty());
        DataAccessResourceFailureException failure =
            new DataAccessResourceFailureException("mysql unavailable");
        when(turnStore.loadRecent(1L, 7L, 2)).thenThrow(failure);

        assertThatThrownBy(() -> memory.load(1L, 7L)).isSameAs(failure);
    }

    private ConversationHistory history(String... questions) {
        return new ConversationHistory(Arrays.stream(questions)
            .map(question -> new ConversationTurn(question, "answer-" + question))
            .toList());
    }
}
