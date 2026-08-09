package com.videoagent.asr;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "videoagent.ai.asr")
public record AsrProviderProperties(
    String provider,
    String apiKey,
    String model,
    String baseUrl,
    Duration timeout
) {

    private static final String DEFAULT_GROQ_MODEL = "whisper-large-v3-turbo";
    private static final String DEFAULT_GROQ_BASE_URL = "https://api.groq.com/openai/v1";
    private static final String DEFAULT_DASHSCOPE_MODEL = "fun-asr-flash-2026-06-15";
    private static final String DEFAULT_DASHSCOPE_BASE_URL =
        "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private static final String DASHSCOPE_GENERATION_PATH =
        "/api/v1/services/aigc/multimodal-generation/generation";

    public AsrProviderProperties {
        provider = defaultIfBlank(provider, "mock").toLowerCase(java.util.Locale.ROOT);
        apiKey = apiKey == null ? "" : apiKey.strip();
        model = defaultIfBlank(model, defaultModel(provider));
        baseUrl = defaultIfBlank(baseUrl, defaultBaseUrl(provider));
        timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("ASR timeout must be positive");
        }
    }

    public boolean hasGroqConfiguration() {
        return !apiKey.isBlank() && !model.isBlank() && !baseUrl.isBlank();
    }

    public boolean hasDashScopeConfiguration() {
        return !apiKey.isBlank() && !model.isBlank() && !baseUrl.isBlank();
    }

    public String transcriptionUrl() {
        String normalized = baseUrl.endsWith("/")
            ? baseUrl.substring(0, baseUrl.length() - 1)
            : baseUrl;
        if (normalized.endsWith("/audio/transcriptions")) {
            return normalized;
        }
        return normalized + "/audio/transcriptions";
    }

    public String generationUrl() {
        String normalized = stripTrailingSlash(baseUrl);
        if (normalized.endsWith(DASHSCOPE_GENERATION_PATH)) {
            return normalized;
        }
        return normalized + DASHSCOPE_GENERATION_PATH;
    }

    private static String defaultModel(String provider) {
        return provider.equals("dashscope") ? DEFAULT_DASHSCOPE_MODEL : DEFAULT_GROQ_MODEL;
    }

    private static String defaultBaseUrl(String provider) {
        return provider.equals("dashscope") ? DEFAULT_DASHSCOPE_BASE_URL : DEFAULT_GROQ_BASE_URL;
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
