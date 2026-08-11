package com.videoagent.agent.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videoagent.agent.config.AgentProperties;
import com.videoagent.summary.provider.SummaryProviderProperties;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class PlannerProviderConfigurationTest {

    private final PlannerProviderConfiguration configuration = new PlannerProviderConfiguration();

    @Test
    void shouldUseMockWhenProviderIsMock() {
        RetrievalPlannerProvider provider = configuration.retrievalPlannerProvider(
            new AgentProperties("mock", 4, 15_000, 120_000, 12, 12_000, ""),
            new SummaryProviderProperties("openai", "", "", "", Duration.ofSeconds(5), 0, null)
        );
        assertThat(provider).isInstanceOf(MockRetrievalPlannerProvider.class);
    }

    @Test
    void shouldFailFastWhenRealPlannerMissingLlmConfig() {
        assertThatThrownBy(() -> configuration.retrievalPlannerProvider(
            new AgentProperties("llm", 4, 15_000, 120_000, 12, 12_000, ""),
            new SummaryProviderProperties("openai", "", "", "", Duration.ofSeconds(5), 0, null)
        )).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("AGENT_PLANNER_PROVIDER");
    }

    @Test
    void shouldBuildRealPlannerWhenLlmConfigured() {
        RetrievalPlannerProvider provider = configuration.retrievalPlannerProvider(
            new AgentProperties("llm", 4, 15_000, 120_000, 12, 12_000, ""),
            new SummaryProviderProperties("openai", "test-key", "deepseek-v4-flash", "https://api.deepseek.com",
                Duration.ofSeconds(30), 0, "json_object")
        );
        assertThat(provider).isInstanceOf(LangChain4jRetrievalPlanner.class);
    }

    @Test
    void shouldRejectUnknownProvider() {
        assertThatThrownBy(() -> configuration.retrievalPlannerProvider(
            new AgentProperties("unknown", 4, 15_000, 120_000, 12, 12_000, ""),
            new SummaryProviderProperties("openai", "", "", "", Duration.ofSeconds(5), 0, null)
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported AGENT_PLANNER_PROVIDER");
    }
}
