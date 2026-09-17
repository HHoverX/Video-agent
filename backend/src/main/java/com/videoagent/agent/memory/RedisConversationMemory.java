package com.videoagent.agent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.videoagent.agent.config.ConversationMemoryProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RedisConversationMemory implements ConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(RedisConversationMemory.class);

    private final ConversationRedisStore redisStore;
    private final ConversationCompactionScheduler compactionScheduler;
    private final ConversationMemoryProperties properties;
    private final ConversationMemoryMetrics metrics;

    public RedisConversationMemory(
        ConversationRedisStore redisStore,
        ConversationCompactionScheduler compactionScheduler,
        ConversationMemoryProperties properties,
        ConversationMemoryMetrics metrics
    ) {
        this.redisStore = redisStore;
        this.compactionScheduler = compactionScheduler;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public ConversationHistory load(long userId, long videoId, String requestId) {
        try {
            ConversationHistory history = redisStore.load(userId, videoId)
                .boundedTo(properties.maxContextChars());
            metrics.increment("load.success");
            metrics.recordRecentTurns(history.recentTurns().size());
            metrics.recordSummaryChars(history.summary().length());
            return history;
        } catch (RuntimeException | JsonProcessingException exception) {
            metrics.increment("load.failure");
            log.warn("event=memory.load.failure requestId={} userId={} videoId={} exceptionClass={}",
                requestId, userId, videoId, exception.getClass().getSimpleName());
            return ConversationHistory.empty();
        }
    }

    @Override
    public void appendTurn(
        long userId,
        long videoId,
        ConversationTurn turn,
        String requestId
    ) {
        long recentCount;
        try {
            recentCount = redisStore.append(userId, videoId, turn);
            metrics.increment("append.success");
            metrics.recordRecentTurns(recentCount);
        } catch (RuntimeException | JsonProcessingException exception) {
            metrics.increment("append.failure");
            log.warn("event=memory.append.failure requestId={} userId={} videoId={} exceptionClass={}",
                requestId, userId, videoId, exception.getClass().getSimpleName());
            return;
        }

        if (recentCount < properties.compactTriggerTurns()) {
            return;
        }
        try {
            compactionScheduler.schedule(userId, videoId);
        } catch (RuntimeException exception) {
            metrics.increment("compact.rejected");
            log.warn("event=memory.compact.schedule_failure requestId={} userId={} videoId={} exceptionClass={}",
                requestId, userId, videoId, exception.getClass().getSimpleName());
        }
    }

    static String recentKey(long userId, long videoId) {
        return ConversationRedisStore.recentKey(userId, videoId);
    }

    static String summaryKey(long userId, long videoId) {
        return ConversationRedisStore.summaryKey(userId, videoId);
    }

    static String lockKey(long userId, long videoId) {
        return ConversationRedisStore.lockKey(userId, videoId);
    }
}
