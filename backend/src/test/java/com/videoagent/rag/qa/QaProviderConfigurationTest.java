package com.videoagent.rag.qa;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.summary.provider.SummaryProviderProperties;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class QaProviderConfigurationTest {

    private final QaProviderConfiguration configuration = new QaProviderConfiguration();

    @Test
    void shouldKeepMockAndRealProviderSelectionBehavior() {
        assertThat(configuration.videoQaProvider(new SummaryProviderProperties(
            null, null, null, null, null, null, null
        ))).isInstanceOf(MockVideoQaProvider.class);

        assertThat(configuration.videoQaProvider(new SummaryProviderProperties(
            "openai", "test-key", "gpt-test", "", Duration.ofSeconds(5), 1, "json_schema"
        ))).isInstanceOf(LangChain4jVideoQaProvider.class);
    }
}
