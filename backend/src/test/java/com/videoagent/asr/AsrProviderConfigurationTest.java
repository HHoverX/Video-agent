package com.videoagent.asr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

class AsrProviderConfigurationTest {

    private final AsrProviderConfiguration configuration = new AsrProviderConfiguration();
    private final AsrResultValidator validator = new AsrResultValidator();

    @Test
    void shouldUseMockByDefault() {
        AsrProvider provider = configuration.asrProvider(
            new AsrProviderProperties(null, null, null, null, null, null),
            validator
        );

        assertThat(provider).isInstanceOf(MockAsrProvider.class);
    }

    @Test
    void shouldBuildGroqProviderWithoutCallingNetwork() {
        AsrProvider provider = configuration.asrProvider(
            new AsrProviderProperties(
                "groq",
                "unit-test-placeholder",
                "whisper-large-v3-turbo",
                "https://api.groq.com/openai/v1",
                Duration.ofSeconds(10),
                null
            ),
            validator
        );

        assertThat(provider).isInstanceOf(GroqAsrProvider.class);
    }

    @Test
    void shouldBuildDashScopeProviderWithoutCallingNetwork() {
        AsrProvider provider = configuration.asrProvider(
            new AsrProviderProperties(
                "dashscope",
                "unit-test-placeholder",
                "fun-asr-flash-2026-06-15",
                "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
                Duration.ofSeconds(10),
                null
            ),
            validator
        );

        assertThat(provider).isInstanceOf(DashScopeAsrProvider.class);
    }

    @Test
    void shouldRejectIncompleteGroqAndUnknownProvider() {
        assertThatThrownBy(() -> configuration.asrProvider(
            new AsrProviderProperties("groq", "", null, null, null, null),
            validator
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ASR_API_KEY");

        assertThatThrownBy(() -> configuration.asrProvider(
            new AsrProviderProperties("unknown", "", null, null, null, null),
            validator
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported ASR_PROVIDER");
    }

    @Test
    void shouldRejectIncompleteDashScopeConfiguration() {
        assertThatThrownBy(() -> configuration.asrProvider(
            new AsrProviderProperties("dashscope", "", null, null, null, null),
            validator
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ASR_API_KEY");
    }

    @Test
    void shouldBindAndNormalizeOptionalLanguageHints() {
        AsrProviderProperties properties = Binder.get(new MockEnvironment()
            .withProperty("videoagent.ai.asr.provider", "dashscope")
            .withProperty("videoagent.ai.asr.language-hints", " zh, ,en,zh "))
            .bind("videoagent.ai.asr", Bindable.of(AsrProviderProperties.class))
            .orElseThrow(() -> new AssertionError("ASR properties should bind"));

        assertThat(properties.languageHints()).containsExactly("zh", "en", "zh");
    }
}
