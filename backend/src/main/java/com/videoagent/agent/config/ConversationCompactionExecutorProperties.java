package com.videoagent.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "videoagent.agent.memory.compaction-executor")
public record ConversationCompactionExecutorProperties(
    Integer coreSize,
    Integer maxSize,
    Integer queueCapacity,
    Duration keepAlive
) {

    public ConversationCompactionExecutorProperties {
        coreSize = coreSize == null ? 1 : coreSize;
        maxSize = maxSize == null ? 2 : maxSize;
        queueCapacity = queueCapacity == null ? 100 : queueCapacity;
        keepAlive = keepAlive == null ? Duration.ofSeconds(30) : keepAlive;
        if (coreSize <= 0 || maxSize < coreSize) {
            throw new IllegalArgumentException(
                "Conversation compaction executor sizes must be positive and max-size >= core-size");
        }
        if (queueCapacity < 0) {
            throw new IllegalArgumentException(
                "Conversation compaction executor queue-capacity must not be negative");
        }
        if (keepAlive.isZero() || keepAlive.isNegative()) {
            throw new IllegalArgumentException(
                "Conversation compaction executor keep-alive must be positive");
        }
    }
}
