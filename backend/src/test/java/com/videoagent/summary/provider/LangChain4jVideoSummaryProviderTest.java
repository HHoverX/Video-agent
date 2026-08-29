package com.videoagent.summary.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.asr.TranscriptSegment;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.telemetry.AiUsageMetrics;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.List;
import java.util.ArrayList;

class LangChain4jVideoSummaryProviderTest {

    private final LangChain4jSummaryAiService aiService = mock(LangChain4jSummaryAiService.class);
    private final LangChain4jVideoSummaryProvider provider = new LangChain4jVideoSummaryProvider(aiService, 50_000);

    @Test
    void shouldReturnEvidenceIdDraftAndIncludeAuthoritativeTimesInPrompt() {
        when(aiService.summarize(contains("[E0] [0-2000ms] transcript"))).thenReturn(
            new VideoSummaryDraft(
                "overview",
                List.of(new VideoSummaryDraft.Chapter("chapter", "summary", "E0", "E0")),
                List.of(new VideoSummaryDraft.KeyPoint("point", "E0", "E0"))
            )
        );

        VideoSummaryDraft result = provider.summarize(request());

        assertThat(result.overview()).isEqualTo("overview");
        assertThat(result.chapters().getFirst().startEvidenceId()).isEqualTo("E0");
    }

    @Test
    void shouldMapUnknownRuntimeExceptionToInternalAnalysisError() {
        when(aiService.summarize(contains("<transcript>")))
            .thenThrow(new IllegalStateException("remote 401: secret-token"));

        assertThatThrownBy(() -> provider.summarize(request()))
            .isInstanceOf(VideoAgentException.class)
            .satisfies(exception -> assertThat(((VideoAgentException) exception).errorCode().name())
                .isEqualTo(ErrorCode.INTERNAL_ANALYSIS_ERROR.name()))
            .hasMessageNotContaining("secret-token");
    }

    @Test
    void shouldMapHttp429And5xxAsRetryableSummaryFailure() {
        for (int status : new int[] {408, 429, 500, 503}) {
            when(aiService.summarize(contains("<transcript>")))
                .thenThrow(new HttpException(status, "upstream unavailable"));

            assertThatThrownBy(() -> provider.summarize(request()))
                .isInstanceOf(VideoAgentException.class)
                .satisfies(exception -> assertThat(((VideoAgentException) exception).errorCode().name())
                    .isEqualTo(ErrorCode.LLM_SUMMARY_FAILED.name()));
        }
    }

    @Test
    void shouldMapHttp401403404AsProviderRejected() {
        for (int status : new int[] {400, 401, 403, 404}) {
            when(aiService.summarize(contains("<transcript>")))
                .thenThrow(new HttpException(status, "request rejected"));

            assertThatThrownBy(() -> provider.summarize(request()))
                .isInstanceOf(VideoAgentException.class)
                .satisfies(exception -> assertThat(((VideoAgentException) exception).errorCode().name())
                    .isEqualTo(ErrorCode.LLM_PROVIDER_REJECTED.name()));
        }
    }

    @Test
    void shouldMapInvalidRequestAsProviderRejected() {
        String sentinel = "TOP_SECRET_PROVIDER_BODY_7F31";
        when(aiService.summarize(contains("<transcript>")))
            .thenThrow(new InvalidRequestException(sentinel));

        assertThatThrownBy(() -> provider.summarize(request()))
            .isInstanceOf(VideoAgentException.class)
            .satisfies(exception -> {
                VideoAgentException failure = (VideoAgentException) exception;
                assertThat(failure.errorCode()).isEqualTo(ErrorCode.LLM_PROVIDER_REJECTED);
                assertThat(failure.getMessage())
                    .isEqualTo("LLM 服务拒绝了请求")
                    .doesNotContain(sentinel);
            });
    }

    @Test
    void shouldAllowActualUserPromptsAtOrBelowConfiguredBudgetAndRejectAboveBeforeModelInvocation() {
        VideoSummaryRequest below = requestWithPromptChars(49_999);
        VideoSummaryRequest atLimit = requestWithPromptChars(50_000);
        VideoSummaryRequest above = requestWithPromptChars(50_001);
        when(aiService.summarize(anyString())).thenReturn(draft());

        provider.summarize(below);
        provider.summarize(atLimit);

        assertThatThrownBy(() -> provider.summarize(above))
            .isInstanceOfSatisfying(VideoAgentException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.SUMMARY_INPUT_TOO_LARGE);
                assertThat(exception.getMessage()).doesNotContain("[E", "transcript");
            });
        verify(aiService).summarize(provider.prompt(below));
        verify(aiService).summarize(provider.prompt(atLimit));
        verify(aiService, never()).summarize(provider.prompt(above));
    }

    @Test
    void shouldRecordSummaryLogicalCallAndPromptScaleWithoutClaimingProviderAttempts() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        LangChain4jVideoSummaryProvider telemetryProvider = telemetryProvider(new AiUsageMetrics(meterRegistry));
        when(aiService.summarize(anyString())).thenReturn(draft());

        VideoSummaryRequest request = request();
        telemetryProvider.summarize(request);

        assertThat(meterRegistry.get("videoagent.ai.logical.calls")
            .tag("stage", "summary").tag("outcome", "success").counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("videoagent.ai.logical.duration")
            .tag("stage", "summary").tag("outcome", "success").timer().count()).isEqualTo(1L);
        assertThat(meterRegistry.get("videoagent.ai.input.scale")
            .tag("stage", "summary").tag("input_type", "prompt_chars").summary().totalAmount())
            .isEqualTo(telemetryProvider.prompt(request).length());
        assertThat(meterRegistry.find("videoagent.ai.provider.requests").counter()).isNull();
    }

    @Test
    void shouldKeepSummaryResultWhenMetricsRegistryFails() {
        MeterRegistry failingRegistry = mock(MeterRegistry.class);
        LangChain4jVideoSummaryProvider telemetryProvider = telemetryProvider(new AiUsageMetrics(failingRegistry));
        when(aiService.summarize(anyString())).thenReturn(draft());

        assertThat(telemetryProvider.summarize(request())).isEqualTo(draft());
    }

    @Test
    void shouldRecordPromptScaleButNoLogicalModelCallWhenCapacityGuardRejects() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        LangChain4jVideoSummaryProvider telemetryProvider = telemetryProvider(new AiUsageMetrics(meterRegistry));
        VideoSummaryRequest rejected = requestWithPromptChars(50_001);

        assertThatThrownBy(() -> telemetryProvider.summarize(rejected))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.SUMMARY_INPUT_TOO_LARGE));

        assertThat(meterRegistry.find("videoagent.ai.logical.calls").counter()).isNull();
        assertThat(meterRegistry.get("videoagent.ai.input.scale")
            .tag("stage", "summary").tag("input_type", "prompt_chars").summary().totalAmount())
            .isEqualTo(50_001.0d);
    }

    private LangChain4jVideoSummaryProvider telemetryProvider(AiUsageMetrics usageMetrics) {
        return new LangChain4jVideoSummaryProvider(aiService, new SummaryProviderProperties(
            "openai", "test-key", "gpt-4.1-mini", "", java.time.Duration.ofSeconds(5), 1, "json_schema"
        ), usageMetrics);
    }

    private VideoSummaryRequest request() {
        return new VideoSummaryRequest(
            7L,
            11L,
            List.of(new TranscriptSegment(0, 2_000, "transcript"))
        );
    }

    private VideoSummaryRequest requestWithPromptChars(int promptChars) {
        List<String> texts = new ArrayList<>(List.of("x"));
        while (true) {
            List<String> candidate = new ArrayList<>(texts);
            candidate.add(candidate.size() - 1, "x".repeat(1_800));
            if (provider.prompt(request(candidate)).length() > promptChars) {
                break;
            }
            texts = candidate;
        }
        int delta = promptChars - provider.prompt(request(texts)).length();
        texts.set(texts.size() - 1, "x".repeat(texts.getLast().length() + delta));
        VideoSummaryRequest request = request(texts);
        assertThat(provider.prompt(request)).hasSize(promptChars);
        return request;
    }

    private VideoSummaryRequest request(List<String> texts) {
        return new VideoSummaryRequest(
            7L,
            11L,
            java.util.stream.IntStream.range(0, texts.size())
                .mapToObj(index -> new TranscriptSegment(index * 2_000L, index * 2_000L + 1_000L, texts.get(index)))
                .toList()
        );
    }

    private VideoSummaryDraft draft() {
        return new VideoSummaryDraft(
            "overview",
            List.of(new VideoSummaryDraft.Chapter("chapter", "summary", "E0", "E0")),
            List.of(new VideoSummaryDraft.KeyPoint("point", "E0", "E0"))
        );
    }
}
