package com.videoagent.summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videoagent.asr.TranscriptSegment;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.summary.provider.SummaryChapter;
import com.videoagent.summary.provider.SummaryKeyPoint;
import com.videoagent.summary.provider.VideoSummaryRequest;
import com.videoagent.summary.provider.VideoSummaryResult;

import org.junit.jupiter.api.Test;

import java.util.List;

class SummaryResultValidatorTest {

    private final SummaryResultValidator validator = new SummaryResultValidator();
    private final VideoSummaryRequest request = new VideoSummaryRequest(7L, 11L, List.of(
        new TranscriptSegment(1_000, 3_000, "first"),
        new TranscriptSegment(3_000, 7_000, "second")
    ));

    @Test
    void shouldTrimTextAndSortStructuredItemsByTimestamp() {
        VideoSummaryResult result = new VideoSummaryResult(
            "  overview  ",
            List.of(
                new SummaryChapter("later", "later summary", 3_000, 7_000),
                new SummaryChapter(" first ", " first summary ", 1_000, 3_000)
            ),
            List.of(
                new SummaryKeyPoint("later point", 3_000, 7_000),
                new SummaryKeyPoint(" first point ", 1_000, 3_000)
            )
        );

        VideoSummaryResult validated = validator.validate(request, result);

        assertThat(validated.overview()).isEqualTo("overview");
        assertThat(validated.chapters()).extracting(SummaryChapter::title)
            .containsExactly("first", "later");
        assertThat(validated.keyPoints()).extracting(SummaryKeyPoint::content)
            .containsExactly("first point", "later point");
    }

    @Test
    void shouldRejectTimestampOutsideTranscriptRange() {
        VideoSummaryResult result = new VideoSummaryResult(
            "overview",
            List.of(new SummaryChapter("chapter", "summary", 0, 7_000)),
            List.of(new SummaryKeyPoint("point", 1_000, 3_000))
        );

        assertThatThrownBy(() -> validator.validate(request, result))
            .isInstanceOf(VideoAgentException.class)
            .extracting(exception -> ((VideoAgentException) exception).errorCode().name())
            .isEqualTo("LLM_SUMMARY_INVALID");
    }

    @Test
    void shouldRejectMissingStructuredCollections() {
        VideoSummaryResult result = new VideoSummaryResult("overview", List.of(), List.of());

        assertThatThrownBy(() -> validator.validate(request, result))
            .isInstanceOf(VideoAgentException.class)
            .hasMessageContaining("chapters");
    }
}
