package com.videoagent.summary.provider;

import static dev.langchain4j.model.chat.Capability.RESPONSE_FORMAT_JSON_SCHEMA;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.videoagent.telemetry.AiUsageMetrics;

@Configuration(proxyBeanMethods = false)
public class SummaryProviderConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SummaryProviderConfiguration.class);

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
            case "openai" -> openAiOrMock(properties, usageMetrics);
            default -> throw new IllegalArgumentException(
                "Unsupported LLM_PROVIDER: " + properties.provider()
            );
        };
    }

    private VideoSummaryProvider openAiOrMock(SummaryProviderProperties properties, AiUsageMetrics usageMetrics) {
        if (!properties.hasRealProviderConfiguration()) {
            log.warn("LLM provider=openai is missing API key or model; using MockVideoSummaryProvider");
            return new MockVideoSummaryProvider();
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
