package com.videoagent.agent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.agent.config.ConversationMemoryProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class RedisConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(RedisConversationMemory.class);
    private static final String KEY_PREFIX = "videoagent:agentic-qa:memory:v1:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ConversationMemoryProperties properties;

    public RedisConversationMemory(
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        ConversationMemoryProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Optional<ConversationHistory> load(long userId, long videoId) {
        try {
            List<String> values = redisTemplate.opsForList().range(
                key(userId, videoId),
                -properties.maxTurns(),
                -1
            );
            if (values == null || values.isEmpty()) {
                return Optional.empty();
            }
            List<ConversationTurn> turns = new ArrayList<>(values.size());
            for (String value : values) {
                ConversationTurn turn = objectMapper.readValue(value, ConversationTurn.class);
                if (turn == null) {
                    return Optional.empty();
                }
                turns.add(turn);
            }
            return Optional.of(new ConversationHistory(turns));
        } catch (DataAccessException | JsonProcessingException exception) {
            log.warn("[userId={}][videoId={}][exceptionClass={}] conversation cache read failed; falling back to MySQL",
                userId, videoId, exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public void replace(long userId, long videoId, ConversationHistory history) {
        String redisKey = key(userId, videoId);
        try {
            List<String> values = new ArrayList<>(history.turns().size());
            for (ConversationTurn turn : history.turns()) {
                values.add(objectMapper.writeValueAsString(turn));
            }
            redisTemplate.delete(redisKey);
            if (values.isEmpty()) {
                return;
            }
            ListOperations<String, String> operations = redisTemplate.opsForList();
            operations.rightPushAll(redisKey, values);
            try {
                operations.trim(redisKey, -properties.maxTurns(), -1);
            } finally {
                redisTemplate.expire(redisKey, properties.ttl());
            }
        } catch (DataAccessException | JsonProcessingException exception) {
            log.warn("[userId={}][videoId={}][exceptionClass={}] conversation cache write failed; MySQL history remains authoritative",
                userId, videoId, exception.getClass().getSimpleName());
        }
    }

    static String key(long userId, long videoId) {
        return KEY_PREFIX + userId + ":" + videoId;
    }
}
