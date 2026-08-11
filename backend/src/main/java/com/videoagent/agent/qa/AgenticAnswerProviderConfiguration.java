package com.videoagent.agent.qa;

import com.videoagent.summary.provider.SummaryProviderProperties;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AgenticAnswerProviderConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgenticAnswerProviderConfiguration.class);

    @Bean
    public AgenticAnswerProvider agenticAnswerProvider(SummaryProviderProperties properties) {
        return switch (properties.provider()) {
            case "openai" -> realOrMock(properties);
            default -> new MockAgenticAnswerProvider();
        };
    }

    private AgenticAnswerProvider realOrMock(SummaryProviderProperties properties) {
        if (!properties.hasRealProviderConfiguration()) {
            log.warn("LLM provider=openai is missing API key or model; using MockAgenticAnswerProvider");
            return new MockAgenticAnswerProvider();
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
        return new LangChain4jAgenticAnswerProvider(aiService);
    }
}
