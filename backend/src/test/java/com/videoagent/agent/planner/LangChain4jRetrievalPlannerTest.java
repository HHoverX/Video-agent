package com.videoagent.agent.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.videoagent.agent.config.AgentProperties;
import com.videoagent.agent.context.AgenticQaContext;
import com.videoagent.agent.plan.RetrievalPlan;
import com.videoagent.agent.plan.RetrievalPlanValidator;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.rag.context.QaContextMode;
import com.videoagent.telemetry.AiUsageMetrics;
import com.videoagent.telemetry.QaTelemetryContext;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.TimeoutException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;

class LangChain4jRetrievalPlannerTest {

    private final LangChain4jPlannerAiService aiService = mock(LangChain4jPlannerAiService.class);
    private final LangChain4jRetrievalPlanner planner = new LangChain4jRetrievalPlanner(aiService);
    private final AgentProperties properties =
        new AgentProperties("mock", 4, 15_000L, 120_000L, 12, 12_000, "");
    private final AgenticQaContext context =
        new AgenticQaContext(1L, 7L, 3L, QaContextMode.RAG, true, true, "READY");

    @Test
    void shouldPreserveMissingTimeSoValidatorRejectsRealAdapterOutput() {
        when(aiService.plan(anyString())).thenReturn(new PlannerAiResponse(
            "TIME_LOOKUP",
            "TIME_LOOKUP",
            List.of(new PlannerAction("GET_TRANSCRIPT_BY_TIME", null, null, 15_000L))
        ));

        RetrievalPlan plan = planner.plan(context, "三分钟附近讲了什么？");

        assertThat(plan.actions().getFirst().timeMs()).isNull();
        assertThatThrownBy(() -> new RetrievalPlanValidator(properties).validate(plan, context))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.getMessage()).contains("timeMs"));
    }

    @Test
    void shouldPreserveMissingOptionalWindow() {
        when(aiService.plan(anyString())).thenReturn(new PlannerAiResponse(
            "TIME_LOOKUP",
            "ignored",
            List.of(new PlannerAction("GET_TRANSCRIPT_BY_TIME", null, 200_000L, null))
        ));

        RetrievalPlan plan = planner.plan(context, "3分20秒讲了什么？");

        assertThat(plan.actions().getFirst().windowMs()).isNull();
        new RetrievalPlanValidator(properties).validate(plan, context);
    }

    @Test
    void shouldClassifyTransientProviderFailures() {
        for (RuntimeException failure : List.of(
            new HttpException(429, "rate limited"),
            new HttpException(503, "unavailable"),
            new TimeoutException("timed out")
        )) {
            org.mockito.Mockito.doThrow(failure).when(aiService).plan(anyString());
            assertThatThrownBy(() -> planner.plan(context, "q"))
                .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.AGENT_PLANNER_FAILED));
        }
    }

    @Test
    void shouldClassifyAuthenticationAndModelFailuresAsProviderRejected() {
        for (RuntimeException failure : List.of(
            new HttpException(401, "unauthorized"),
            new HttpException(403, "forbidden"),
            new InvalidRequestException("invalid model")
        )) {
            org.mockito.Mockito.doThrow(failure).when(aiService).plan(anyString());
            assertThatThrownBy(() -> planner.plan(context, "q"))
                .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.LLM_PROVIDER_REJECTED));
        }
    }

    @Test
    void shouldRecordOneAgenticPlannerLogicalCallWithBoundedTags() {
        when(aiService.plan(anyString())).thenReturn(new PlannerAiResponse(
            "SEMANTIC_SEARCH", "ignored", List.of(new PlannerAction("SEARCH_TRANSCRIPT", "q", null, null))
        ));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LangChain4jRetrievalPlanner telemetryPlanner = new LangChain4jRetrievalPlanner(
            aiService, "openai", "planner-model", 1, new AiUsageMetrics(registry)
        );

        telemetryPlanner.plan(context, "question", new QaTelemetryContext("request-1", 7L, 3L));

        assertThat(registry.get("videoagent.ai.logical.calls")
            .tag("scope", "qa").tag("stage", "qa_planner").tag("mode", "agentic")
            .tag("outcome", "success").counter().count()).isEqualTo(1.0d);
        assertThat(registry.get("videoagent.ai.input.scale")
            .tag("scope", "qa").tag("stage", "qa_planner").tag("input_type", "question_chars")
            .summary().totalAmount()).isEqualTo(8.0d);
        assertThat(registry.getMeters().stream()
            .flatMap(meter -> meter.getId().getTags().stream())
            .map(tag -> tag.getValue())).doesNotContain("request-1", "7", "3", "question");
    }

    @Test
    void shouldIgnoreMetricRecordingFailures() {
        when(aiService.plan(anyString())).thenReturn(new PlannerAiResponse(
            "SEMANTIC_SEARCH", "ignored", List.of(new PlannerAction("SEARCH_TRANSCRIPT", "q", null, null))
        ));
        LangChain4jRetrievalPlanner telemetryPlanner = new LangChain4jRetrievalPlanner(
            aiService, "openai", "planner-model", 1, new AiUsageMetrics(mock(io.micrometer.core.instrument.MeterRegistry.class))
        );

        RetrievalPlan result = telemetryPlanner.plan(
            context, "question", new QaTelemetryContext("request-1", 7L, 3L)
        );

        assertThat(result.actions()).hasSize(1);
    }
}
