package com.videoagent.rag.qa;

import static dev.langchain4j.model.chat.Capability.RESPONSE_FORMAT_JSON_SCHEMA;

import com.videoagent.summary.provider.SummaryProviderProperties;
import com.videoagent.telemetry.AiUsageMetrics;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class QaProviderConfiguration {

    @Bean
    public VideoQaProvider videoQaProvider(
        SummaryProviderProperties properties,
        AiUsageMetrics usageMetrics
    ) {
        return switch (properties.provider()) {
            case "mock" -> new MockVideoQaProvider();
            case "openai" -> realProvider(properties, usageMetrics);
            default -> throw new IllegalArgumentException(
                "Unsupported LLM_PROVIDER for Basic QA: " + properties.provider()
            );
        };
    }

    VideoQaProvider videoQaProvider(SummaryProviderProperties properties) {
        return videoQaProvider(properties, AiUsageMetrics.noop());
    }

    private VideoQaProvider realProvider(
        SummaryProviderProperties properties,
        AiUsageMetrics usageMetrics
    ) {
        if (!properties.hasRealProviderConfiguration()) {
            throw new IllegalStateException(
                "LLM_PROVIDER=openai requires LLM_API_KEY and LLM_MODEL for Basic QA"
            );
        }
        var builder = OpenAiChatModel.builder()
            .apiKey(properties.apiKey())
            .modelName(properties.model())
            .timeout(properties.timeout())
            .maxRetries(properties.maxRetries());
        switch (properties.structuredOutputMode()) {
            case "json_schema" -> builder
                .supportedCapabilities(RESPONSE_FORMAT_JSON_SCHEMA)
                .strictJsonSchema(true);
            case "json_object" -> builder.responseFormat("json_object");
            case "prompting" -> {
                // LangChain4j AI Services embeds the target structure in the prompt.
            }
            default -> throw new IllegalStateException("Validated structured output mode is unknown");
        }
        if (!properties.baseUrl().isBlank()) {
            builder.baseUrl(properties.baseUrl());
        }
        ChatModel chatModel = builder.build();
        LangChain4jQaAiService aiService = AiServices.create(
            LangChain4jQaAiService.class,
            chatModel
        );
        return new LangChain4jVideoQaProvider(aiService, properties, usageMetrics);
    }
}
