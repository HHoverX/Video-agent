package com.videoagent.analysis.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "videoagent.analysis.sse")
public record AnalysisEventProperties(Duration timeout) {

    public AnalysisEventProperties {
        timeout = timeout == null ? Duration.ofMinutes(30) : timeout;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("videoagent.analysis.sse.timeout must be positive");
        }
    }
}
