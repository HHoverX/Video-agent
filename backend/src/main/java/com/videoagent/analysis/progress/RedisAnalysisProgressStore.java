package com.videoagent.analysis.progress;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.analysis.dto.AnalysisProgressSnapshot;
import com.videoagent.analysis.service.AnalysisProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class RedisAnalysisProgressStore implements AnalysisProgressStore {

    private static final Logger log = LoggerFactory.getLogger(RedisAnalysisProgressStore.class);
    private static final String KEY_PREFIX = "video:analysis:progress:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AnalysisProperties properties;

    public RedisAnalysisProgressStore(
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        AnalysisProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void save(long taskId, AnalysisProgressSnapshot progress) {
        try {
            redisTemplate.opsForValue().set(
                key(taskId),
                objectMapper.writeValueAsString(progress),
                properties.progressTtl()
            );
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("[taskId={}][stage={}] Redis progress write failed; MySQL remains authoritative",
                taskId, progress.stage(), exception);
        }
    }

    @Override
    public Optional<AnalysisProgressSnapshot> find(long taskId) {
        try {
            String value = redisTemplate.opsForValue().get(key(taskId));
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, AnalysisProgressSnapshot.class));
        } catch (RuntimeException | JsonProcessingException exception) {
            log.warn("[taskId={}] Redis progress read failed; falling back to MySQL", taskId, exception);
            return Optional.empty();
        }
    }

    public static String key(long taskId) {
        return KEY_PREFIX + taskId;
    }
}
