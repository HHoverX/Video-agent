package com.videoagent.summary.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "videoagent.ai.llm")
public record SummaryProviderProperties(
    String provider,
    String apiKey,
    String model,
    String baseUrl,
    Duration timeout,
    Integer maxRetries,
    String structuredOutputMode
) {
    public SummaryProviderProperties {
        provider = defaultIfBlank(provider, "mock").toLowerCase();
        apiKey = apiKey == null ? "" : apiKey.strip();
        model = model == null ? "" : model.strip();
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
        timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        maxRetries = maxRetries == null ? 1 : maxRetries;
        structuredOutputMode = defaultIfBlank(structuredOutputMode, "json_schema")
            .toLowerCase(java.util.Locale.ROOT);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("LLM timeout must be positive");
        }
        if (maxRetries < 0 || maxRetries > 3) {
            throw new IllegalArgumentException("LLM maxRetries must be between 0 and 3");
        }
        if (!structuredOutputMode.equals("json_schema")
            && !structuredOutputMode.equals("json_object")
            && !structuredOutputMode.equals("prompting")) {
            throw new IllegalArgumentException(
                "LLM structuredOutputMode must be json_schema, json_object or prompting"
            );
        }
    }

    public boolean hasRealProviderConfiguration() {
        return !apiKey.isBlank() && !model.isBlank();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
