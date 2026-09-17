package com.videoagent.agent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.videoagent.agent.config.ConversationMemoryProperties;
import com.videoagent.agent.memory.ConversationRedisStore.CommitResult;
import com.videoagent.agent.memory.ConversationRedisStore.CompactionSnapshot;
import com.videoagent.agent.memory.summary.ConversationSummaryProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class ConversationCompactionService {

    private static final Logger log = LoggerFactory.getLogger(ConversationCompactionService.class);

    private final ConversationRedisStore redisStore;
    private final ConversationSummaryProvider summaryProvider;
    private final ConversationMemoryProperties properties;
    private final ConversationMemoryMetrics metrics;

    public ConversationCompactionService(
        ConversationRedisStore redisStore,
        ConversationSummaryProvider summaryProvider,
        ConversationMemoryProperties properties,
        ConversationMemoryMetrics metrics
    ) {
        this.redisStore = redisStore;
        this.summaryProvider = summaryProvider;
        this.properties = properties;
        this.metrics = metrics;
    }

    public void compact(long userId, long videoId) {
        long startedAtNanos = System.nanoTime();
        metrics.increment("compact.started");
        String token = UUID.randomUUID().toString();
        boolean lockAcquired = false;
        try {
            lockAcquired = redisStore.tryAcquireLock(userId, videoId, token);
            if (!lockAcquired) {
                metrics.increment("compact.skipped");
                return;
            }

            Optional<CompactionSnapshot> snapshot = redisStore.snapshot(userId, videoId);
            if (snapshot.isEmpty()) {
                metrics.increment("compact.skipped");
                return;
            }

            String newSummary = summaryProvider.summarize(
                snapshot.get().oldSummary(),
                snapshot.get().turns(),
                properties.maxSummaryChars()
            );
            if (newSummary == null || newSummary.isBlank()
                || newSummary.length() > properties.maxSummaryChars()) {
                throw new IllegalStateException("Conversation summary was empty or exceeded its character budget");
            }

            CommitResult result = redisStore.commit(
                userId,
                videoId,
                token,
                snapshot.get(),
                newSummary.strip()
            );
            if (result == CommitResult.SUCCESS) {
                metrics.increment("compact.success");
                metrics.recordSummaryChars(newSummary.strip().length());
                log.info("event=memory.compact.success userId={} videoId={}", userId, videoId);
            } else {
                metrics.increment("compact.conflict");
                log.warn("event=memory.compact.conflict userId={} videoId={} reason={}",
                    userId, videoId, result);
            }
        } catch (RuntimeException | JsonProcessingException exception) {
            metrics.increment("compact.failure");
            log.warn("event=memory.compact.failure userId={} videoId={} exceptionClass={}",
                userId, videoId, exception.getClass().getSimpleName());
        } finally {
            if (lockAcquired) {
                try {
                    redisStore.releaseLock(userId, videoId, token);
                } catch (RuntimeException exception) {
                    metrics.increment("compact.failure");
                    log.warn("event=memory.compact.unlock_failure userId={} videoId={} exceptionClass={}",
                        userId, videoId, exception.getClass().getSimpleName());
                }
            }
            metrics.recordCompactDuration(System.nanoTime() - startedAtNanos);
        }
    }
}
