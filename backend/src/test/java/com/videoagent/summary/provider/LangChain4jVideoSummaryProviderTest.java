package com.videoagent.summary.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.videoagent.asr.TranscriptSegment;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.summary.service.SummaryResultValidator;

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
    void shouldMapAiServiceFailureWithoutLeakingProviderDetails() {
        when(aiService.summarize(contains("<transcript>")))
            .thenThrow(new IllegalStateException("remote 401: secret-token"));

        assertThatThrownBy(() -> provider.summarize(request()))
            .isInstanceOf(VideoAgentException.class)
            .satisfies(exception -> assertThat(((VideoAgentException) exception).errorCode().name())
                .isEqualTo("LLM_SUMMARY_FAILED"))
            .hasMessage("LLM 结构化总结调用失败")
            .hasMessageNotContaining("secret-token");
    }

    private VideoSummaryRequest request() {
        return new VideoSummaryRequest(
            7L,
            11L,
            List.of(new TranscriptSegment(0, 2_000, "transcript"))
        );
    }
}
