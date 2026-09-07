package com.videoagent.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.agent.config.ConversationMemoryProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

class RedisConversationMemoryTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ListOperations<String, String> listOperations = mock(ListOperations.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private RedisConversationMemory memory;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        memory = new RedisConversationMemory(
            redisTemplate,
            objectMapper,
            new ConversationMemoryProperties(Duration.ofHours(24), 2, 6_000)
        );
    }

    @Test
    void shouldReturnCacheHitInOldestToNewestOrder() throws Exception {
        ConversationTurn first = new ConversationTurn("q1", "a1");
        ConversationTurn second = new ConversationTurn("q2", "a2");
        when(listOperations.range(RedisConversationMemory.key(1L, 7L), -2, -1))
            .thenReturn(List.of(
                objectMapper.writeValueAsString(first),
                objectMapper.writeValueAsString(second)
            ));

        assertThat(memory.load(1L, 7L)).contains(new ConversationHistory(List.of(first, second)));
    }

    @Test
    void shouldReturnEmptyOptionalOnMiss() {
        when(listOperations.range(RedisConversationMemory.key(1L, 7L), -2, -1)).thenReturn(List.of());

        assertThat(memory.load(1L, 7L)).isEmpty();
    }

    @Test
    void shouldKeepUserAndVideoKeysIsolated() {
        assertThat(RedisConversationMemory.key(1L, 7L))
            .isNotEqualTo(RedisConversationMemory.key(2L, 7L))
            .isNotEqualTo(RedisConversationMemory.key(1L, 8L));
    }

    @Test
    void shouldReplaceRecentTurnsUsingExistingV1FormatAndTtl() throws Exception {
        ConversationTurn first = new ConversationTurn("q1", "a1");
        ConversationTurn second = new ConversationTurn("q2", "a2");

        memory.replace(1L, 7L, new ConversationHistory(List.of(first, second)));

        String key = RedisConversationMemory.key(1L, 7L);
        verify(redisTemplate).delete(key);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> values = ArgumentCaptor.forClass(Collection.class);
        verify(listOperations).rightPushAll(org.mockito.ArgumentMatchers.eq(key), values.capture());
        assertThat(values.getValue()).extracting(value -> {
            try {
                return objectMapper.readValue(value, ConversationTurn.class);
            } catch (JsonProcessingException exception) {
                throw new AssertionError(exception);
            }
        }).containsExactly(first, second);
        verify(listOperations).trim(key, -2, -1);
        verify(redisTemplate).expire(key, Duration.ofHours(24));
    }

    @Test
    void shouldDeleteStaleKeyWhenMysqlHistoryIsEmpty() {
        memory.replace(1L, 7L, ConversationHistory.empty());

        verify(redisTemplate).delete(RedisConversationMemory.key(1L, 7L));
    }

    @Test
    void shouldTreatRedisReadAndSerializationFailuresAsMisses() {
        String key = RedisConversationMemory.key(1L, 7L);
        when(listOperations.range(key, -2, -1))
            .thenThrow(new DataAccessResourceFailureException("redis unavailable"));
        assertThat(memory.load(1L, 7L)).isEmpty();

        org.mockito.Mockito.doReturn(List.of("{")).when(listOperations).range(key, -2, -1);
        assertThat(memory.load(1L, 7L)).isEmpty();
    }

    @Test
    void shouldNotPropagateRedisWriteFailure() {
        when(listOperations.rightPushAll(anyString(), anyCollection()))
            .thenThrow(new DataAccessResourceFailureException("redis unavailable"));

        assertThatCode(() -> memory.replace(
            1L, 7L, new ConversationHistory(List.of(new ConversationTurn("q", "a")))
        )).doesNotThrowAnyException();
    }

    @Test
    void shouldNotPropagateTurnSerializationFailure() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(org.mockito.ArgumentMatchers.any()))
            .thenThrow(new JsonProcessingException("serialization failed") { });
        RedisConversationMemory failingMemory = new RedisConversationMemory(
            redisTemplate,
            failingMapper,
            new ConversationMemoryProperties(Duration.ofHours(24), 2, 6_000)
        );

        assertThatCode(() -> failingMemory.replace(
            1L, 7L, new ConversationHistory(List.of(new ConversationTurn("q", "a")))
        )).doesNotThrowAnyException();
    }

    @Test
    void shouldPropagateUnexpectedRuntimeFailure() {
        when(listOperations.range(anyString(), anyLong(), anyLong()))
            .thenThrow(new IllegalStateException("programming bug"));

        assertThatThrownBy(() -> memory.load(1L, 7L)).isInstanceOf(IllegalStateException.class);
    }
}
