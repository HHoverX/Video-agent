package com.videoagent.summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videoagent.asr.TranscriptSegment;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.summary.provider.VideoSummaryDraft;
import com.videoagent.summary.provider.VideoSummaryRequest;
import com.videoagent.summary.provider.VideoSummaryResult;

import org.junit.jupiter.api.Test;

import java.util.List;

class SummaryEvidenceResolverTest {

    private final SummaryEvidenceResolver resolver = new SummaryEvidenceResolver();

    @Test
    void shouldResolveSingleAndContiguousEvidenceRangesToExactTranscriptBoundaries() {
        VideoSummaryResult result = resolver.resolve(request(), new VideoSummaryDraft(
            "overview",
            List.of(
                new VideoSummaryDraft.Chapter("first", "summary", "E0", "E0"),
                new VideoSummaryDraft.Chapter("range", "summary", "E0", "E2")
            ),
            List.of(
                new VideoSummaryDraft.KeyPoint("single", "E1", "E1"),
                new VideoSummaryDraft.KeyPoint("range", "E1", "E2")
            )
        ));

        assertThat(result.chapters()).extracting(chapter -> List.of(chapter.startMs(), chapter.endMs()))
            .containsExactly(List.of(240L, 10_340L), List.of(240L, 30_960L));
        assertThat(result.keyPoints()).extracting(point -> List.of(point.startMs(), point.endMs()))
            .containsExactly(List.of(10_341L, 21_780L), List.of(10_341L, 30_960L));
    }

    @Test
    void shouldRejectMalformedUnknownAndReversedEvidenceReferences() {
        for (String[] references : List.of(
            new String[] {"e0", "E0"},
            new String[] {"E3", "E3"},
            new String[] {"E2", "E1"}
        )) {
            assertThatThrownBy(() -> resolver.resolve(request(), new VideoSummaryDraft(
                "overview",
                List.of(new VideoSummaryDraft.Chapter("chapter", "summary", references[0], references[1])),
                List.of()
            ))).isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.LLM_SUMMARY_INVALID)
            );
        }
    }

    @Test
    void shouldKeepDraftFreeOfAuthoritativeTimestampFields() {
        assertThat(VideoSummaryDraft.Chapter.class.getRecordComponents())
            .extracting(component -> component.getName())
            .containsExactly("title", "summary", "startEvidenceId", "endEvidenceId");
        assertThat(VideoSummaryDraft.KeyPoint.class.getRecordComponents())
            .extracting(component -> component.getName())
            .containsExactly("content", "startEvidenceId", "endEvidenceId");
    }

    private VideoSummaryRequest request() {
        return new VideoSummaryRequest(7L, 11L, List.of(
            new TranscriptSegment(240L, 10_340L, "first"),
            new TranscriptSegment(10_341L, 21_780L, "second"),
            new TranscriptSegment(21_781L, 30_960L, "third")
        ));
    }
}
