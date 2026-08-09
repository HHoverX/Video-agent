package com.videoagent.analysis.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "videoagent.analysis")
public record AnalysisProperties(
    String topic,
    String consumerGroup,
    String analysisType,
    String modelVersion,
    Duration progressTtl
) {
    public AnalysisProperties {
        topic = defaultIfBlank(topic, "VIDEO_ANALYZE_TOPIC");
        consumerGroup = defaultIfBlank(consumerGroup, "videoagent-analysis-consumer");
        analysisType = defaultIfBlank(analysisType, "STRUCTURED_SUMMARY");
        modelVersion = defaultIfBlank(modelVersion, "m5-langchain4j-structured-v1");
        progressTtl = progressTtl == null ? Duration.ofHours(24) : progressTtl;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
