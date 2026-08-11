package com.videoagent.agent.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.summary.provider.SummaryProviderProperties;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class AgenticAnswerProviderConfigurationTest {

    private final AgenticAnswerProviderConfiguration configuration =
        new AgenticAnswerProviderConfiguration();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldUseMockOnlyWhenExplicitlyConfigured() {
        AgenticAnswerProvider provider = configuration.agenticAnswerProvider(
            properties("mock", "", ""), objectMapper);
        assertThat(provider).isInstanceOf(MockAgenticAnswerProvider.class);
    }

    @Test
    void shouldBuildRealProviderWhenConfigured() {
        AgenticAnswerProvider provider = configuration.agenticAnswerProvider(
            properties("openai", "test-key", "model"), objectMapper);
        assertThat(provider).isInstanceOf(LangChain4jAgenticAnswerProvider.class);
    }

    @Test
    void shouldFailFastForUnknownProvider() {
        assertThatThrownBy(() -> configuration.agenticAnswerProvider(
            properties("deepssek", "test-key", "model"), objectMapper))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported LLM_PROVIDER");
    }

    @Test
    void shouldFailFastWhenRealProviderConfigurationIsIncomplete() {
        assertThatThrownBy(() -> configuration.agenticAnswerProvider(
            properties("openai", "", "model"), objectMapper))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LLM_API_KEY");
    }

    private SummaryProviderProperties properties(String provider, String apiKey, String model) {
        return new SummaryProviderProperties(
            provider, apiKey, model, "https://example.test", Duration.ofSeconds(5), 0, "json_object");
    }
}
