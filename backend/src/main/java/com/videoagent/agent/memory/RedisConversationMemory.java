package com.videoagent.agent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.agent.config.ConversationMemoryProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RedisConversationMemory implements ConversationMemory {

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

    @Override
    public ConversationHistory load(long userId, long videoId) {
        try {
            List<String> values = redisTemplate.opsForList().range(
                key(userId, videoId),
                -properties.maxTurns(),
                -1
            );
            if (values == null || values.isEmpty()) {
                return ConversationHistory.empty();
            }
            List<ConversationTurn> turns = new ArrayList<>(values.size());
            for (String value : values) {
                ConversationTurn turn = objectMapper.readValue(value, ConversationTurn.class);
                if (turn != null) {
                    turns.add(turn);
                }
            }
            return new ConversationHistory(turns);
        } catch (DataAccessException | JsonProcessingException exception) {
            log.warn("[userId={}][videoId={}][exceptionClass={}] conversation memory read failed; continuing without history",
                userId, videoId, exception.getClass().getSimpleName());
            return ConversationHistory.empty();
        }
    }

    @Override
    public void appendTurn(long userId, long videoId, ConversationTurn turn) {
        String redisKey = key(userId, videoId);
        try {
            String value = objectMapper.writeValueAsString(turn);
            ListOperations<String, String> operations = redisTemplate.opsForList();
            operations.rightPush(redisKey, value);
            try {
                operations.trim(redisKey, -properties.maxTurns(), -1);
            } finally {
                redisTemplate.expire(redisKey, properties.ttl());
            }
        } catch (DataAccessException | JsonProcessingException exception) {
            log.warn("[userId={}][videoId={}][exceptionClass={}] conversation memory write failed; qa response remains successful",
                userId, videoId, exception.getClass().getSimpleName());
        }
    }

    static String key(long userId, long videoId) {
        return KEY_PREFIX + userId + ":" + videoId;
    }
}
