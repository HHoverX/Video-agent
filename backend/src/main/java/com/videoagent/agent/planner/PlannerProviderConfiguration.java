package com.videoagent.agent.planner;

import com.videoagent.agent.config.AgentProperties;
import com.videoagent.summary.provider.SummaryProviderProperties;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the retrieval planner. Fail-fast: an explicit real (llm) planner
 * requires the LLM configuration; missing config is a startup error, never a
 * silent fallback to Mock.
 */
@Configuration(proxyBeanMethods = false)
public class PlannerProviderConfiguration {

    @Bean
    public RetrievalPlannerProvider retrievalPlannerProvider(
        AgentProperties agentProperties,
        SummaryProviderProperties llmProperties
    ) {
        return switch (agentProperties.plannerProvider()) {
            case "mock" -> new MockRetrievalPlannerProvider();
            case "llm" -> realPlanner(agentProperties, llmProperties);
            default -> throw new IllegalArgumentException(
                "Unsupported AGENT_PLANNER_PROVIDER: " + agentProperties.plannerProvider()
            );
        };
    }

    private RetrievalPlannerProvider realPlanner(
        AgentProperties agentProperties,
        SummaryProviderProperties llmProperties
    ) {
        if (!llmProperties.hasRealProviderConfiguration()) {
            throw new IllegalStateException(
                "AGENT_PLANNER_PROVIDER=llm requires LLM configuration "
                    + "(LLM_API_KEY and LLM_MODEL); missing config must not silently fall back to Mock"
            );
        }
        ChatModel chatModel = OpenAiChatModel.builder()
            .apiKey(llmProperties.apiKey())
            .modelName(agentProperties.plannerModel().isBlank()
                ? llmProperties.model()
                : agentProperties.plannerModel())
            .baseUrl(llmProperties.baseUrl().isBlank()
                ? "https://api.openai.com/v1"
                : llmProperties.baseUrl())
            .timeout(llmProperties.timeout())
            .maxRetries(llmProperties.maxRetries())
            .responseFormat("json_object")
            .build();
        LangChain4jPlannerAiService aiService = AiServices.create(
            LangChain4jPlannerAiService.class,
            chatModel
        );
        return new LangChain4jRetrievalPlanner(aiService);
    }
}
