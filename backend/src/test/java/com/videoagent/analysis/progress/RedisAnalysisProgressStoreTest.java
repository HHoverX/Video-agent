package com.videoagent.analysis.progress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.analysis.dto.AnalysisProgressSnapshot;
import com.videoagent.analysis.service.AnalysisProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

class RedisAnalysisProgressStoreTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private RedisAnalysisProgressStore store;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        AnalysisProperties properties = new AnalysisProperties(
            "VIDEO_ANALYZE_TOPIC",
            "test-consumer",
            "FRAMEWORK",
            "m3-simulation-v1",
            Duration.ofHours(24),
            Duration.ZERO
        );
        store = new RedisAnalysisProgressStore(redisTemplate, new ObjectMapper(), properties);
    }

    @Test
    void shouldWriteJsonProgressWithTwentyFourHourTtl() {
        AnalysisProgressSnapshot progress = new AnalysisProgressSnapshot(
            "PROCESSING", "ANALYZING", 40, "正在模拟分析"
        );

        store.save(101L, progress);

        verify(valueOperations).set(
            "video:analysis:progress:101",
            "{\"status\":\"PROCESSING\",\"stage\":\"ANALYZING\",\"progress\":40,\"message\":\"正在模拟分析\"}",
            Duration.ofHours(24)
        );
    }

    @Test
    void shouldReadJsonProgress() {
        when(valueOperations.get("video:analysis:progress:101")).thenReturn(
            "{\"status\":\"PROCESSING\",\"stage\":\"SAVING\",\"progress\":90,\"message\":\"正在保存结果\"}"
        );

        assertThat(store.find(101L)).contains(new AnalysisProgressSnapshot(
            "PROCESSING", "SAVING", 90, "正在保存结果"
        ));
    }
}
