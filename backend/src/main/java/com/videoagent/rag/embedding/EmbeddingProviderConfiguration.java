package com.videoagent.rag.embedding;

import com.videoagent.rag.config.EmbeddingProperties;
import com.videoagent.telemetry.AiUsageMetrics;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the embedding provider. Fail-fast: an explicit real provider
 * (openai / dashscope) requires apiKey, baseUrl, model and dimension to be
 * configured. Missing configuration is a startup error, never a silent fallback
 * to Mock, because silently degrading retrieval silently degrades answers.
 */
@Configuration(proxyBeanMethods = false)
public class EmbeddingProviderConfiguration {

    EmbeddingProvider embeddingProvider(EmbeddingProperties properties) {
        return embeddingProvider(properties, AiUsageMetrics.noop());
    }

    @Bean
    public EmbeddingProvider embeddingProvider(EmbeddingProperties properties, AiUsageMetrics usageMetrics) {
        return switch (properties.provider()) {
            case "mock" -> new MockEmbeddingProvider();
            case "openai", "dashscope" -> requireRealConfiguration(properties, usageMetrics);
            default -> throw new IllegalArgumentException(
                "Unsupported EMBEDDING_PROVIDER: " + properties.provider()
            );
        };
    }

    private EmbeddingProvider requireRealConfiguration(EmbeddingProperties properties, AiUsageMetrics usageMetrics) {
        if (properties.apiKey().isBlank()) {
            throw new IllegalStateException(
                "EMBEDDING_PROVIDER=" + properties.provider()
                    + " requires EMBEDDING_API_KEY to be configured"
            );
        }
        if (properties.baseUrl().isBlank()) {
            throw new IllegalStateException(
                "EMBEDDING_PROVIDER=" + properties.provider()
                    + " requires EMBEDDING_BASE_URL to be configured"
            );
        }
        if (properties.model().isBlank()) {
            throw new IllegalStateException(
                "EMBEDDING_PROVIDER=" + properties.provider()
                    + " requires EMBEDDING_MODEL to be configured"
            );
        }
        return new RealEmbeddingProvider(properties, usageMetrics);
    }
}
