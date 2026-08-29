package com.videoagent.summary.provider;

import static dev.langchain4j.model.chat.Capability.RESPONSE_FORMAT_JSON_SCHEMA;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.videoagent.telemetry.AiUsageMetrics;

@Configuration(proxyBeanMethods = false)
public class SummaryProviderConfiguration {

    VideoSummaryProvider videoSummaryProvider(SummaryProviderProperties properties) {
        return videoSummaryProvider(properties, AiUsageMetrics.noop());
    }

    @Bean
    public VideoSummaryProvider videoSummaryProvider(
        SummaryProviderProperties properties,
        AiUsageMetrics usageMetrics
    ) {
        return switch (properties.provider()) {
            case "mock" -> new MockVideoSummaryProvider();
            case "openai" -> openAiProvider(properties, usageMetrics);
            default -> throw new IllegalArgumentException(
                "Unsupported LLM_PROVIDER: " + properties.provider()
            );
        };
    }

    private VideoSummaryProvider openAiProvider(SummaryProviderProperties properties, AiUsageMetrics usageMetrics) {
        if (!properties.hasRealProviderConfiguration()) {
            throw new IllegalStateException(
                "LLM_PROVIDER=openai requires LLM_API_KEY and LLM_MODEL for Summary"
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
        LangChain4jSummaryAiService aiService = AiServices.create(
            LangChain4jSummaryAiService.class,
            chatModel
        );
        return new LangChain4jVideoSummaryProvider(aiService, properties, usageMetrics);
    }
}
