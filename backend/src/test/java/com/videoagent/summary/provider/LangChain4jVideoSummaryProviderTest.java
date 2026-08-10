package com.videoagent.summary.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.videoagent.asr.TranscriptSegment;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.summary.service.SummaryResultValidator;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;

import org.junit.jupiter.api.Test;

import java.util.List;

class LangChain4jVideoSummaryProviderTest {

    private final LangChain4jSummaryAiService aiService = mock(LangChain4jSummaryAiService.class);
    private final LangChain4jVideoSummaryProvider provider = new LangChain4jVideoSummaryProvider(
        aiService,
        new SummaryResultValidator()
    );

    @Test
    void shouldValidateStructuredAiServiceResult() {
        when(aiService.summarize(contains("[0-2000] transcript"))).thenReturn(
            new VideoSummaryResult(
                "overview",
                List.of(new SummaryChapter("chapter", "summary", 0, 2_000)),
                List.of(new SummaryKeyPoint("point", 0, 2_000))
            )
        );

        VideoSummaryResult result = provider.summarize(request());

        assertThat(result.overview()).isEqualTo("overview");
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
        when(aiService.summarize(contains("<transcript>")))
            .thenThrow(new InvalidRequestException("bad model"));

        assertThatThrownBy(() -> provider.summarize(request()))
            .isInstanceOf(VideoAgentException.class)
            .satisfies(exception -> assertThat(((VideoAgentException) exception).errorCode().name())
                .isEqualTo(ErrorCode.LLM_PROVIDER_REJECTED.name()));
    }

    private VideoSummaryRequest request() {
        return new VideoSummaryRequest(
            7L,
            11L,
            List.of(new TranscriptSegment(0, 2_000, "transcript"))
        );
    }
}
