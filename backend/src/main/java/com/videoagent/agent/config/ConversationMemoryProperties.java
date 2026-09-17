package com.videoagent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "videoagent.agent.memory")
public record ConversationMemoryProperties(
    Duration ttl,
    Integer recentTurns,
    Integer compactTriggerTurns,
    Integer compactBatchTurns,
    Integer maxSummaryChars,
    Integer maxContextChars,
    Duration compactLockTtl
) {

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final int DEFAULT_RECENT_TURNS = 6;
    private static final int DEFAULT_COMPACT_TRIGGER_TURNS = 10;
    private static final int DEFAULT_COMPACT_BATCH_TURNS = 4;
    private static final int DEFAULT_MAX_SUMMARY_CHARS = 2_000;
    private static final int DEFAULT_MAX_CONTEXT_CHARS = 6_000;
    private static final Duration DEFAULT_COMPACT_LOCK_TTL = Duration.ofMinutes(3);

    public ConversationMemoryProperties {
        ttl = ttl == null ? DEFAULT_TTL : ttl;
        recentTurns = recentTurns == null ? DEFAULT_RECENT_TURNS : recentTurns;
        compactTriggerTurns = compactTriggerTurns == null
            ? DEFAULT_COMPACT_TRIGGER_TURNS : compactTriggerTurns;
        compactBatchTurns = compactBatchTurns == null
            ? DEFAULT_COMPACT_BATCH_TURNS : compactBatchTurns;
        maxSummaryChars = maxSummaryChars == null ? DEFAULT_MAX_SUMMARY_CHARS : maxSummaryChars;
        maxContextChars = maxContextChars == null ? DEFAULT_MAX_CONTEXT_CHARS : maxContextChars;
        compactLockTtl = compactLockTtl == null ? DEFAULT_COMPACT_LOCK_TTL : compactLockTtl;
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("AGENT_MEMORY_TTL must be positive");
        }
        if (recentTurns <= 0) {
            throw new IllegalArgumentException("AGENT_MEMORY_RECENT_TURNS must be positive");
        }
        if (compactTriggerTurns <= recentTurns) {
            throw new IllegalArgumentException(
                "AGENT_MEMORY_COMPACT_TRIGGER_TURNS must be greater than recent turns");
        }
        if (compactBatchTurns <= 0
            || compactTriggerTurns - compactBatchTurns != recentTurns) {
            throw new IllegalArgumentException(
                "AGENT_MEMORY_COMPACT_BATCH_TURNS must leave exactly recent turns at the trigger");
        }
        if (maxSummaryChars <= 0 || maxSummaryChars > maxContextChars) {
            throw new IllegalArgumentException(
                "AGENT_MEMORY_MAX_SUMMARY_CHARS must be positive and not exceed max context chars");
        }
        if (maxContextChars < 256 || maxContextChars > 12_000) {
            throw new IllegalArgumentException(
                "AGENT_MEMORY_MAX_CONTEXT_CHARS must be between 256 and 12000");
        }
        if (compactLockTtl.isZero() || compactLockTtl.isNegative()) {
            throw new IllegalArgumentException("AGENT_MEMORY_COMPACT_LOCK_TTL must be positive");
        }
    }
}
