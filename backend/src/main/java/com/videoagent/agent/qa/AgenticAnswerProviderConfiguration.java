package com.videoagent.agent.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoagent.summary.provider.SummaryProviderProperties;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AgenticAnswerProviderConfiguration {

    @Bean
    public AgenticAnswerProvider agenticAnswerProvider(
        SummaryProviderProperties properties,
        ObjectMapper objectMapper
    ) {
        return switch (properties.provider()) {
            case "mock" -> new MockAgenticAnswerProvider();
            case "openai" -> realProvider(properties, objectMapper);
            default -> throw new IllegalArgumentException(
                "Unsupported LLM_PROVIDER for Agentic Answer: " + properties.provider()
            );
        };
    }

    private AgenticAnswerProvider realProvider(
        SummaryProviderProperties properties,
        ObjectMapper objectMapper
    ) {
        if (!properties.hasRealProviderConfiguration()) {
            throw new IllegalStateException(
                "LLM_PROVIDER=openai requires LLM_API_KEY and LLM_MODEL for Agentic Answer"
            );
        }
        ChatModel chatModel = OpenAiChatModel.builder()
            .apiKey(properties.apiKey())
            .modelName(properties.model())
            .baseUrl(properties.baseUrl())
            .timeout(properties.timeout())
            .maxRetries(properties.maxRetries())
            .responseFormat("json_object")
            .build();
        LangChain4jAgenticAnswerAiService aiService = AiServices.create(
            LangChain4jAgenticAnswerAiService.class,
            chatModel
        );
        return new LangChain4jAgenticAnswerProvider(aiService, objectMapper);
    }
}
