package com.videoagent.asr;

import com.videoagent.telemetry.AiUsageMetrics;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AsrProviderConfiguration {

    AsrProvider asrProvider(
        AsrProviderProperties properties,
        AsrResultValidator validator
    ) {
        return asrProvider(properties, validator, AiUsageMetrics.noop());
    }

    @Bean
    public AsrProvider asrProvider(
        AsrProviderProperties properties,
        AsrResultValidator validator,
        AiUsageMetrics usageMetrics
    ) {
        return switch (properties.provider()) {
            case "mock" -> new MockAsrProvider();
            case "groq" -> groqProvider(properties, validator, usageMetrics);
            case "dashscope" -> dashScopeProvider(properties, validator, usageMetrics);
            default -> throw new IllegalArgumentException(
                "Unsupported ASR_PROVIDER: " + properties.provider()
            );
        };
    }

    private AsrProvider groqProvider(
        AsrProviderProperties properties,
        AsrResultValidator validator,
        AiUsageMetrics usageMetrics
    ) {
        if (!properties.hasGroqConfiguration()) {
            throw new IllegalArgumentException(
                "ASR_PROVIDER=groq requires ASR_API_KEY, ASR_MODEL and ASR_BASE_URL"
            );
        }
        return new GroqAsrProvider(properties, validator, usageMetrics);
    }

    private AsrProvider dashScopeProvider(
        AsrProviderProperties properties,
        AsrResultValidator validator,
        AiUsageMetrics usageMetrics
    ) {
        if (!properties.hasDashScopeConfiguration()) {
            throw new IllegalArgumentException(
                "ASR_PROVIDER=dashscope requires ASR_API_KEY, ASR_MODEL and ASR_BASE_URL"
            );
        }
        return new DashScopeAsrProvider(properties, validator, usageMetrics);
    }
}
