package com.videoagent.summary.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videoagent.summary.service.SummaryResultValidator;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class SummaryProviderConfigurationTest {

    private final SummaryProviderConfiguration configuration = new SummaryProviderConfiguration();
    private final SummaryResultValidator validator = new SummaryResultValidator();

    @Test
    void shouldUseMockByDefaultAndWhenOpenAiConfigurationIsIncomplete() {
        VideoSummaryProvider defaultProvider = configuration.videoSummaryProvider(
            new SummaryProviderProperties(null, null, null, null, null, null),
            validator
        );
        VideoSummaryProvider incompleteOpenAi = configuration.videoSummaryProvider(
            new SummaryProviderProperties(
                "openai", "", "gpt-4.1-mini", "", Duration.ofSeconds(5), 0
            ),
            validator
        );

        assertThat(defaultProvider).isInstanceOf(MockVideoSummaryProvider.class);
        assertThat(incompleteOpenAi).isInstanceOf(MockVideoSummaryProvider.class);
    }

    @Test
    void shouldBuildRealProviderWithoutCallingNetworkWhenConfigurationIsComplete() {
        VideoSummaryProvider provider = configuration.videoSummaryProvider(
            new SummaryProviderProperties(
                "openai", "test-key", "gpt-4.1-mini", "", Duration.ofSeconds(5), 0
            ),
            validator
        );

        assertThat(provider).isInstanceOf(LangChain4jVideoSummaryProvider.class);
    }

    @Test
    void shouldRejectUnboundedRetriesAndUnknownProvider() {
        assertThatThrownBy(() -> new SummaryProviderProperties(
            "mock", "", "", "", Duration.ofSeconds(5), 4
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxRetries");
        assertThatThrownBy(() -> configuration.videoSummaryProvider(
            new SummaryProviderProperties(
                "unknown", "", "", "", Duration.ofSeconds(5), 0
            ),
            validator
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported LLM_PROVIDER");
    }
}
