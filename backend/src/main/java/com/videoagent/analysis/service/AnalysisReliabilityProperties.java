package com.videoagent.analysis.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "videoagent.analysis.reliability")
public record AnalysisReliabilityProperties(
    int maxAttempts,
    Duration processingLease,
    Duration heartbeatInterval
) {

    public AnalysisReliabilityProperties {
        maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
        processingLease = processingLease == null ? Duration.ofMinutes(15) : processingLease;
        heartbeatInterval = heartbeatInterval == null ? Duration.ofMinutes(2) : heartbeatInterval;
        if (processingLease.isZero() || processingLease.isNegative()) {
            throw new IllegalArgumentException("videoagent.analysis.reliability.processing-lease must be positive");
        }
        if (heartbeatInterval.isZero() || heartbeatInterval.isNegative()) {
            throw new IllegalArgumentException("videoagent.analysis.reliability.heartbeat-interval must be positive");
        }
        if (!heartbeatInterval.minus(processingLease).isNegative()) {
            throw new IllegalArgumentException(
                "heartbeat interval must be smaller than processing lease"
            );
        }
    }
}
