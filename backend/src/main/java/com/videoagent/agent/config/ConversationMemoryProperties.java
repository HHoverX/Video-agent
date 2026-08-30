package com.videoagent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "videoagent.agent.memory")
public record ConversationMemoryProperties(
    Duration ttl,
    Integer maxTurns,
    Integer maxHistoryChars
) {

    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final int DEFAULT_MAX_TURNS = 6;
    private static final int DEFAULT_MAX_HISTORY_CHARS = 6_000;
    private static final int MIN_MAX_HISTORY_CHARS = 256;
    private static final int MAX_MAX_HISTORY_CHARS = 12_000;
    private static final int MAX_TURNS_LIMIT = 20;

    public ConversationMemoryProperties {
        ttl = ttl == null ? DEFAULT_TTL : ttl;
        maxTurns = maxTurns == null ? DEFAULT_MAX_TURNS : maxTurns;
        maxHistoryChars = maxHistoryChars == null ? DEFAULT_MAX_HISTORY_CHARS : maxHistoryChars;
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("AGENT_MEMORY_TTL must be positive");
        }
        if (maxTurns <= 0 || maxTurns > MAX_TURNS_LIMIT) {
            throw new IllegalArgumentException("AGENT_MEMORY_MAX_TURNS must be between 1 and 20");
        }
        if (maxHistoryChars < MIN_MAX_HISTORY_CHARS || maxHistoryChars > MAX_MAX_HISTORY_CHARS) {
            throw new IllegalArgumentException("AGENT_MEMORY_MAX_HISTORY_CHARS must be between 256 and 12000");
        }
    }
}
