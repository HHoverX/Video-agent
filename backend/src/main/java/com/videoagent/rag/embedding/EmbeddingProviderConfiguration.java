package com.videoagent.rag.embedding;

import com.videoagent.rag.config.EmbeddingProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class EmbeddingProviderConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingProviderConfiguration.class);

    @Bean
    public EmbeddingProvider embeddingProvider(EmbeddingProperties properties) {
        return switch (properties.provider()) {
            case "mock" -> new MockEmbeddingProvider();
            case "openai", "dashscope" -> realOrMock(properties);
            default -> throw new IllegalArgumentException(
                "Unsupported EMBEDDING_PROVIDER: " + properties.provider()
            );
        };
    }

    private EmbeddingProvider realOrMock(EmbeddingProperties properties) {
        if (!properties.hasRealProviderConfiguration()) {
            log.warn("Embedding provider={} is missing API key, model or base URL; using MockEmbeddingProvider",
                properties.provider());
            return new MockEmbeddingProvider();
        }
        return new RealEmbeddingProvider(properties);
    }
}
