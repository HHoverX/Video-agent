package com.videoagent.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Embedding provider configuration. Kept fully separate from ASR/LLM config so
 * a real embedding endpoint (even from the same vendor) is never assumed to
 * share API keys or URLs with the ASR or LLM providers.
 */
@ConfigurationProperties(prefix = "videoagent.rag.embedding")
public record EmbeddingProperties(
    String provider,
    String apiKey,
    String baseUrl,
    String model,
    int dimension,
    Duration timeout
) {

    public EmbeddingProperties {
        provider = provider == null || provider.isBlank() ? "mock" : provider.strip().toLowerCase();
        apiKey = apiKey == null ? "" : apiKey.strip();
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
        model = model == null ? "" : model.strip();
        dimension = dimension <= 0 ? 384 : dimension;
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Embedding timeout must be positive");
        }
    }

    public boolean hasRealProviderConfiguration() {
        return !apiKey.isBlank() && !model.isBlank() && !baseUrl.isBlank();
    }
}
