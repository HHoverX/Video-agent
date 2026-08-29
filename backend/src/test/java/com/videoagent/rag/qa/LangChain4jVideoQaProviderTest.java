package com.videoagent.rag.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.summary.provider.SummaryProviderProperties;
import com.videoagent.telemetry.AiUsageMetrics;
import com.videoagent.telemetry.QaTelemetryContext;
import com.videoagent.telemetry.QaTelemetryRoute;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

class LangChain4jVideoQaProviderTest {

    private final LangChain4jQaAiService aiService = mock(LangChain4jQaAiService.class);

    @Test
    void shouldRecordOneBasicLogicalCallAndExplicitInputScales() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LangChain4jVideoQaProvider provider = provider(new AiUsageMetrics(registry));
        when(aiService.answer(anyString())).thenReturn(new VideoQaAiResponse("answer", List.of(0)));

        VideoQaResult result = provider.answer(
            request(),
            new QaTelemetryContext("request-1", 7L, 3L),
            QaTelemetryRoute.BASIC_DIRECT
        );

        assertThat(result.answer()).isEqualTo("answer");
        assertThat(registry.get("videoagent.ai.logical.calls")
            .tag("scope", "qa").tag("stage", "qa_basic")
            .tag("mode", "basic_direct").tag("outcome", "success").counter().count()).isEqualTo(1.0d);
        assertThat(inputAmount(registry, "question_chars")).isEqualTo(8.0d);
        assertThat(inputAmount(registry, "context_chars")).isEqualTo(6.0d);
        assertThat(inputAmount(registry, "context_items")).isEqualTo(2.0d);
        assertThat(registry.find("videoagent.ai.provider.requests").counter()).isNull();
    }

    @Test
    void shouldPreserveProviderErrorAndRecordBoundedFailureCategory() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LangChain4jVideoQaProvider provider = provider(new AiUsageMetrics(registry));
        when(aiService.answer(anyString())).thenThrow(new IllegalStateException("secret raw failure"));

        assertThatThrownBy(() -> provider.answer(
            request(),
            new QaTelemetryContext("request-1", 7L, 3L),
            QaTelemetryRoute.BASIC_RAG
        )).isInstanceOfSatisfying(VideoAgentException.class, exception -> {
            assertThat(exception.errorCode()).isEqualTo(ErrorCode.LLM_SUMMARY_FAILED);
            assertThat(exception.getMessage()).doesNotContain("secret raw failure");
        });

        assertThat(registry.get("videoagent.ai.logical.calls")
            .tag("scope", "qa").tag("outcome", "failure")
            .tag("error_category", ErrorCode.LLM_SUMMARY_FAILED.name()).counter().count()).isEqualTo(1.0d);
        assertThat(registry.getMeters().stream()
            .flatMap(meter -> meter.getId().getTags().stream())
            .map(tag -> tag.getValue())).doesNotContain("request-1", "7", "3", "secret raw failure");
    }

    @Test
    void shouldKeepSuccessfulResultWhenMetricsRegistryFails() {
        MeterRegistry failingRegistry = mock(MeterRegistry.class);
        LangChain4jVideoQaProvider provider = provider(new AiUsageMetrics(failingRegistry));
        when(aiService.answer(anyString())).thenReturn(new VideoQaAiResponse("answer", List.of(0)));

        assertThat(provider.answer(
            request(),
            new QaTelemetryContext("request-1", 7L, 3L),
            QaTelemetryRoute.BASIC_DIRECT
        ).answer()).isEqualTo("answer");
    }

    private LangChain4jVideoQaProvider provider(AiUsageMetrics usageMetrics) {
        return new LangChain4jVideoQaProvider(aiService, new SummaryProviderProperties(
            "openai", "test-key", "gpt-test", "", Duration.ofSeconds(5), 1, "json_schema"
        ), usageMetrics);
    }

    private VideoQaRequest request() {
        return new VideoQaRequest(7L, "question", List.of(
            new VideoQaRequest.ContextItem(0, "first", 0L, 1_000L),
            new VideoQaRequest.ContextItem(1, "二", 1_000L, 2_000L)
        ));
    }

    private double inputAmount(SimpleMeterRegistry registry, String inputType) {
        return registry.get("videoagent.ai.input.scale")
            .tag("scope", "qa").tag("stage", "qa_basic")
            .tag("input_type", inputType).summary().totalAmount();
    }
}
