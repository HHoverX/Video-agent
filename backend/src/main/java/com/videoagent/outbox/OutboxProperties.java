package com.videoagent.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "videoagent.outbox")
public record OutboxProperties(
    long publishIntervalMillis,
    Duration initialBackoff,
    double backoffMultiplier,
    int maxAttempts,
    int batchSize
) {

    public OutboxProperties {
        publishIntervalMillis = publishIntervalMillis <= 0 ? 5_000 : publishIntervalMillis;
        initialBackoff = initialBackoff == null ? Duration.ofSeconds(5) : initialBackoff;
        backoffMultiplier = backoffMultiplier <= 0 ? 2.0 : backoffMultiplier;
        maxAttempts = maxAttempts <= 0 ? 15 : maxAttempts;
        batchSize = batchSize <= 0 ? 20 : batchSize;
        if (initialBackoff.isZero() || initialBackoff.isNegative()) {
            throw new IllegalArgumentException("videoagent.outbox.initial-backoff must be positive");
        }
    }
}
