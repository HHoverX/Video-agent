package com.videoagent.analysis.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

@ConfigurationProperties(prefix = "videoagent.analysis")
public record AnalysisProperties(
    String topic,
    String consumerGroup,
    String analysisType,
    String modelVersion,
    Duration progressTtl,
    Duration maxVideoDuration
) {
    public AnalysisProperties(
        String topic,
        String consumerGroup,
        String analysisType,
        String modelVersion,
        Duration progressTtl
    ) {
        this(topic, consumerGroup, analysisType, modelVersion, progressTtl, null);
    }

    @ConstructorBinding
    public AnalysisProperties {
        topic = defaultIfBlank(topic, "VIDEO_ANALYZE_TOPIC");
        consumerGroup = defaultIfBlank(consumerGroup, "videoagent-analysis-consumer");
        analysisType = defaultIfBlank(analysisType, "STRUCTURED_SUMMARY");
        modelVersion = defaultIfBlank(modelVersion, "m5-langchain4j-structured-v1");
        progressTtl = progressTtl == null ? Duration.ofHours(24) : progressTtl;
        maxVideoDuration = maxVideoDuration == null ? Duration.ofHours(1) : maxVideoDuration;
        if (maxVideoDuration.isZero() || maxVideoDuration.isNegative()) {
            throw new IllegalArgumentException("Analysis maxVideoDuration must be positive");
        }
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
