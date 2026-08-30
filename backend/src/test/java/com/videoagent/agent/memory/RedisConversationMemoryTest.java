package com.videoagent.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.agent.config.ConversationMemoryProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class RedisConversationMemoryTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ListOperations<String, String> listOperations = mock(ListOperations.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, List<String>> valuesByKey = new HashMap<>();
    private RedisConversationMemory memory;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.rightPush(anyString(), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            List<String> values = valuesByKey.computeIfAbsent(key, ignored -> new ArrayList<>());
            values.add(value);
            return (long) values.size();
        });
        when(listOperations.range(anyString(), anyLong(), anyLong())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            long start = invocation.getArgument(1);
            List<String> values = valuesByKey.getOrDefault(key, List.of());
            int from = start < 0 ? Math.max(0, values.size() + (int) start) : (int) start;
            return new ArrayList<>(values.subList(Math.min(from, values.size()), values.size()));
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            long start = invocation.getArgument(1);
            List<String> values = valuesByKey.get(key);
            if (values != null && start < 0) {
                int from = Math.max(0, values.size() + (int) start);
                valuesByKey.put(key, new ArrayList<>(values.subList(from, values.size())));
            }
            return null;
        }).when(listOperations).trim(anyString(), anyLong(), anyLong());
        memory = new RedisConversationMemory(
            redisTemplate,
            objectMapper,
            new ConversationMemoryProperties(Duration.ofHours(24), 2, 6_000)
        );
    }

    @Test
    void shouldReadBackTurnsForSameUserAndVideoInOrder() {
        ConversationTurn first = new ConversationTurn("q1", "a1");
        ConversationTurn second = new ConversationTurn("q2", "a2");

        memory.appendTurn(1L, 7L, first);
        memory.appendTurn(1L, 7L, second);

        assertThat(memory.load(1L, 7L).turns()).containsExactly(first, second);
    }

    @Test
    void shouldIsolateDifferentUsers() {
        memory.appendTurn(1L, 7L, new ConversationTurn("q", "a"));

        assertThat(memory.load(2L, 7L).turns()).isEmpty();
        assertThat(RedisConversationMemory.key(1L, 7L))
            .isNotEqualTo(RedisConversationMemory.key(2L, 7L));
    }

    @Test
    void shouldIsolateDifferentVideos() {
        memory.appendTurn(1L, 7L, new ConversationTurn("q", "a"));

        assertThat(memory.load(1L, 8L).turns()).isEmpty();
        assertThat(RedisConversationMemory.key(1L, 7L))
            .isNotEqualTo(RedisConversationMemory.key(1L, 8L));
    }

    @Test
    void shouldKeepOnlyMostRecentConfiguredTurns() {
        memory.appendTurn(1L, 7L, new ConversationTurn("q1", "a1"));
        memory.appendTurn(1L, 7L, new ConversationTurn("q2", "a2"));
        memory.appendTurn(1L, 7L, new ConversationTurn("q3", "a3"));

        assertThat(memory.load(1L, 7L).turns()).containsExactly(
            new ConversationTurn("q2", "a2"),
            new ConversationTurn("q3", "a3")
        );
        verify(listOperations).range(
            "videoagent:agentic-qa:memory:v1:1:7", -2, -1);
    }

    @Test
    void shouldSetConfiguredTtl() {
        memory.appendTurn(1L, 7L, new ConversationTurn("q", "a"));

        verify(redisTemplate).expire(
            "videoagent:agentic-qa:memory:v1:1:7", Duration.ofHours(24));
    }

    @Test
    void shouldFailOpenWhenRedisReadFails() {
        when(listOperations.range("videoagent:agentic-qa:memory:v1:1:7", -2, -1))
            .thenThrow(new DataAccessResourceFailureException("redis unavailable"));

        assertThat(memory.load(1L, 7L)).isEqualTo(ConversationHistory.empty());
    }

    @Test
    void shouldNotPropagateRedisWriteFailure() {
        when(listOperations.rightPush(anyString(), anyString()))
            .thenThrow(new DataAccessResourceFailureException("redis unavailable"));

        assertThatCode(() -> memory.appendTurn(1L, 7L, new ConversationTurn("q", "a")))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldStoreOneCompleteTurnAsOneRedisElement() throws Exception {
        ConversationTurn turn = new ConversationTurn("question", "answer");

        memory.appendTurn(1L, 7L, turn);

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(listOperations, times(1)).rightPush(
            org.mockito.ArgumentMatchers.eq("videoagent:agentic-qa:memory:v1:1:7"),
            value.capture()
        );
        assertThat(objectMapper.readValue(value.getValue(), ConversationTurn.class)).isEqualTo(turn);
    }

    @Test
    void shouldFailOpenWhenStoredJsonIsInvalid() {
        valuesByKey.put("videoagent:agentic-qa:memory:v1:1:7", List.of("{"));

        assertThat(memory.load(1L, 7L)).isEqualTo(ConversationHistory.empty());
    }

    @Test
    void shouldFailOpenWhenTurnSerializationFails() throws Exception {
        ObjectMapper failingObjectMapper = mock(ObjectMapper.class);
        org.mockito.Mockito.when(failingObjectMapper.writeValueAsString(org.mockito.ArgumentMatchers.any()))
            .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("serialization failed") { });
        RedisConversationMemory failingMemory = new RedisConversationMemory(
            redisTemplate,
            failingObjectMapper,
            new ConversationMemoryProperties(Duration.ofHours(24), 2, 6_000)
        );

        assertThatCode(() -> failingMemory.appendTurn(1L, 7L, new ConversationTurn("q", "a")))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldPropagateUnexpectedRuntimeFailure() {
        when(listOperations.range("videoagent:agentic-qa:memory:v1:1:7", -2, -1))
            .thenThrow(new IllegalStateException("programming bug"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> memory.load(1L, 7L))
            .isInstanceOf(IllegalStateException.class);
    }
}
