package com.videoagent.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

class AnalysisRateLimiterTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private AnalysisRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new AnalysisRateLimiter(
            redisTemplate,
            new AnalysisProtectionProperties(
                new AnalysisProtectionProperties.RateLimit(5, 1, Duration.ofSeconds(30), 1),
                3
            )
        );
    }

    @Test
    void shouldRejectWhenAtomicScriptReportsNoToken() {
        when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(0L);

        assertThatThrownBy(() -> limiter.checkAllowed(7L))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.ANALYSIS_RATE_LIMITED)
            );
    }

    @Test
    void shouldFailOpenWhenRedisIsUnavailable() {
        when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
            .thenThrow(new DataAccessResourceFailureException("redis down"));

        limiter.checkAllowed(7L);
    }
}
