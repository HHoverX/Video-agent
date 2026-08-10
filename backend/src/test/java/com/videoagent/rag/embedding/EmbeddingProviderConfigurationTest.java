package com.videoagent.rag.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videoagent.rag.config.EmbeddingProperties;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class EmbeddingProviderConfigurationTest {

    private final EmbeddingProviderConfiguration configuration = new EmbeddingProviderConfiguration();

    @Test
    void shouldUseMockWhenProviderIsMock() {
        EmbeddingProvider provider = configuration.embeddingProvider(
            new EmbeddingProperties("mock", "", "", "", 384, Duration.ofSeconds(5))
        );
        assertThat(provider).isInstanceOf(MockEmbeddingProvider.class);
    }

    @Test
    void shouldUseMockWhenProviderBlank() {
        EmbeddingProvider provider = configuration.embeddingProvider(
            new EmbeddingProperties(null, "", "", "", 384, Duration.ofSeconds(5))
        );
        assertThat(provider).isInstanceOf(MockEmbeddingProvider.class);
    }

    @Test
    void shouldFailFastWhenRealProviderMissingApiKey() {
        assertThatThrownBy(() -> configuration.embeddingProvider(
            new EmbeddingProperties("openai", "", "https://emb.example", "model", 384, Duration.ofSeconds(5))
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("EMBEDDING_API_KEY");
    }

    @Test
    void shouldFailFastWhenRealProviderMissingBaseUrl() {
        assertThatThrownBy(() -> configuration.embeddingProvider(
            new EmbeddingProperties("dashscope", "key", "", "model", 384, Duration.ofSeconds(5))
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("EMBEDDING_BASE_URL");
    }

    @Test
    void shouldFailFastWhenRealProviderMissingModel() {
        assertThatThrownBy(() -> configuration.embeddingProvider(
            new EmbeddingProperties("openai", "key", "https://emb.example", "", 384, Duration.ofSeconds(5))
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("EMBEDDING_MODEL");
    }

    @Test
    void shouldFailFastWhenRealProviderDimensionInvalid() {
        assertThatThrownBy(() -> new EmbeddingProperties(
            "openai", "key", "https://emb.example", "model", 0, Duration.ofSeconds(5)
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("EMBEDDING_DIMENSION");
    }

    @Test
    void shouldBuildRealProviderWhenConfigurationComplete() {
        EmbeddingProvider provider = configuration.embeddingProvider(
            new EmbeddingProperties("openai", "key", "https://emb.example", "model", 768, Duration.ofSeconds(5))
        );
        assertThat(provider).isInstanceOf(RealEmbeddingProvider.class);
        assertThat(provider.dimension()).isEqualTo(768);
    }

    @Test
    void shouldRejectUnknownProvider() {
        assertThatThrownBy(() -> configuration.embeddingProvider(
            new EmbeddingProperties("unknown", "", "", "", 384, Duration.ofSeconds(5))
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported EMBEDDING_PROVIDER");
    }
}
