package com.videoagent.agent.memory.summary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.agent.config.ConversationMemoryProperties;
import com.videoagent.summary.provider.SummaryProviderProperties;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class ConversationSummaryProviderConfiguration {

    private static final Duration LOCK_SAFETY_MARGIN = Duration.ofSeconds(30);

    @Bean
    public ConversationSummaryProvider conversationSummaryProvider(
        SummaryProviderProperties llmProperties,
        ConversationMemoryProperties memoryProperties,
        ObjectMapper objectMapper
    ) {
        return switch (llmProperties.provider()) {
            case "mock" -> new MockConversationSummaryProvider();
            case "openai" -> openAiProvider(llmProperties, memoryProperties, objectMapper);
            default -> throw new IllegalArgumentException(
                "Unsupported LLM_PROVIDER for conversation summary: " + llmProperties.provider());
        };
    }

    private ConversationSummaryProvider openAiProvider(
        SummaryProviderProperties llmProperties,
        ConversationMemoryProperties memoryProperties,
        ObjectMapper objectMapper
    ) {
        if (!llmProperties.hasRealProviderConfiguration()) {
            throw new IllegalStateException(
                "LLM_PROVIDER=openai requires LLM_API_KEY and LLM_MODEL for conversation summary");
        }
        Duration maximumCallTime = llmProperties.timeout()
            .multipliedBy((long) llmProperties.maxRetries() + 1L)
            .plus(LOCK_SAFETY_MARGIN);
        if (memoryProperties.compactLockTtl().compareTo(maximumCallTime) <= 0) {
            throw new IllegalStateException(
                "AGENT_MEMORY_COMPACT_LOCK_TTL must exceed the configured LLM timeout/retry budget plus 30s");
        }

        var builder = OpenAiChatModel.builder()
            .apiKey(llmProperties.apiKey())
            .modelName(llmProperties.model())
            .timeout(llmProperties.timeout())
            .maxRetries(llmProperties.maxRetries());
        if (!llmProperties.baseUrl().isBlank()) {
            builder.baseUrl(llmProperties.baseUrl());
        }
        LangChain4jConversationSummaryAiService aiService = AiServices.create(
            LangChain4jConversationSummaryAiService.class,
            builder.build()
        );
        return new LangChain4jConversationSummaryProvider(aiService, objectMapper);
    }
}
