package com.videoagent.analysis.service;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AnalysisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRateLimiter.class);
    private static final String KEY_PREFIX = "videoagent:analysis:rate:";
    private static final DefaultRedisScript<Long> TOKEN_BUCKET = new DefaultRedisScript<>("""
        local time = redis.call('TIME')
        local now = time[1] * 1000 + math.floor(time[2] / 1000)
        local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens'))
        local updatedAt = tonumber(redis.call('HGET', KEYS[1], 'updatedAt'))
        if tokens == nil or updatedAt == nil then
            tokens = tonumber(ARGV[1])
            updatedAt = now
        else
            local elapsed = math.max(0, now - updatedAt)
            tokens = math.min(tonumber(ARGV[1]), tokens + elapsed * tonumber(ARGV[2]) / tonumber(ARGV[3]))
            updatedAt = now
        end
        local allowed = 0
        if tokens >= tonumber(ARGV[4]) then
            tokens = tokens - tonumber(ARGV[4])
            allowed = 1
        end
        redis.call('HSET', KEYS[1], 'tokens', tokens, 'updatedAt', updatedAt)
        redis.call('PEXPIRE', KEYS[1], ARGV[5])
        return allowed
        """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final AnalysisProtectionProperties properties;

    public AnalysisRateLimiter(
        StringRedisTemplate redisTemplate,
        AnalysisProtectionProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void checkAllowed(long userId) {
        AnalysisProtectionProperties.RateLimit limit = properties.rateLimit();
        try {
            Long allowed = redisTemplate.execute(
                TOKEN_BUCKET,
                List.of(key(userId)),
                limit.capacity().toString(),
                limit.refillTokens().toString(),
                Long.toString(limit.refillPeriod().toMillis()),
                limit.costPerRequest().toString(),
                Long.toString(bucketTtlMillis(limit))
            );
            if (!Long.valueOf(1L).equals(allowed)) {
                log.info("[userId={}][stage=ANALYSIS_RATE_LIMIT] request rejected", userId);
                throw new VideoAgentException(
                    ErrorCode.ANALYSIS_RATE_LIMITED,
                    "分析请求过于频繁，请稍后重试"
                );
            }
        } catch (DataAccessException exception) {
            log.warn(
                "[userId={}][stage=ANALYSIS_RATE_LIMIT][exceptionClass={}] Redis unavailable; using DB protections",
                userId,
                exception.getClass().getSimpleName()
            );
        }
    }

    static String key(long userId) {
        return KEY_PREFIX + userId;
    }

    static long bucketTtlMillis(AnalysisProtectionProperties.RateLimit limit) {
        long refillPeriods = (limit.capacity() + (long) limit.refillTokens() - 1) / limit.refillTokens();
        return Math.multiplyExact(refillPeriods, limit.refillPeriod().toMillis());
    }
}
