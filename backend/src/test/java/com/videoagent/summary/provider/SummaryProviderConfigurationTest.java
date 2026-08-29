package com.videoagent.summary.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


import org.junit.jupiter.api.Test;

import java.time.Duration;

class SummaryProviderConfigurationTest {

    private final SummaryProviderConfiguration configuration = new SummaryProviderConfiguration();

    @Test
    void shouldUseMockOnlyWhenExplicitlyConfigured() {
        VideoSummaryProvider defaultProvider = configuration.videoSummaryProvider(
            new SummaryProviderProperties(null, null, null, null, null, null, null)
        );

        assertThat(defaultProvider).isInstanceOf(MockVideoSummaryProvider.class);
    }

    @Test
    void shouldRejectIncompleteExplicitOpenAiConfiguration() {
        assertThatThrownBy(() -> configuration.videoSummaryProvider(
            new SummaryProviderProperties(
                "openai", "", "gpt-4.1-mini", "", Duration.ofSeconds(5), 0, null
            )
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LLM_API_KEY", "LLM_MODEL");
    }

    @Test
    void shouldBuildRealProviderWithoutCallingNetworkWhenConfigurationIsComplete() {
        VideoSummaryProvider provider = configuration.videoSummaryProvider(
            new SummaryProviderProperties(
                "openai", "test-key", "gpt-4.1-mini", "", Duration.ofSeconds(5), 0, "json_schema"
            )
        );

        assertThat(provider).isInstanceOf(LangChain4jVideoSummaryProvider.class);
    }

    @Test
    void shouldRejectUnboundedRetriesAndUnknownProvider() {
        assertThatThrownBy(() -> new SummaryProviderProperties(
            "mock", "", "", "", Duration.ofSeconds(5), 4, null
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxRetries");
        assertThatThrownBy(() -> configuration.videoSummaryProvider(
            new SummaryProviderProperties(
                "unknown", "", "", "", Duration.ofSeconds(5), 0, null
            )
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported LLM_PROVIDER");
    }

    @Test
    void shouldDefaultAndValidateMaxUserPromptChars() {
        assertThat(new SummaryProviderProperties(null, null, null, null, null, null, null)
            .maxUserPromptChars()).isEqualTo(50_000);
        assertThatThrownBy(() -> new SummaryProviderProperties(
            "mock", "", "", "", Duration.ofSeconds(5), 0, null, 0
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxUserPromptChars");
    }

    @Test
    void shouldBuildOpenAiCompatibleProviderInJsonObjectModeForDeepSeek() {
        VideoSummaryProvider provider = configuration.videoSummaryProvider(
            new SummaryProviderProperties(
                "openai",
                "test-placeholder",
                "deepseek-v4-flash",
                "https://api.deepseek.com",
                Duration.ofSeconds(30),
                0,
                "json_object"
            )
        );

        assertThat(provider).isInstanceOf(LangChain4jVideoSummaryProvider.class);
    }
}
