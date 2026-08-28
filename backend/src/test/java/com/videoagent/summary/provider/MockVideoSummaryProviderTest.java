package com.videoagent.summary.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.asr.TranscriptSegment;

import org.junit.jupiter.api.Test;

import java.util.List;

class MockVideoSummaryProviderTest {

    private final MockVideoSummaryProvider provider = new MockVideoSummaryProvider();

    @Test
    void shouldReturnDeterministicEvidenceIdDraft() {
        VideoSummaryRequest request = new VideoSummaryRequest(7L, 11L, List.of(
            new TranscriptSegment(0, 2_000, "first"),
            new TranscriptSegment(2_000, 4_000, "second"),
            new TranscriptSegment(4_000, 6_000, "third")
        ));

        VideoSummaryDraft first = provider.summarize(request);
        VideoSummaryDraft second = provider.summarize(request);

        assertThat(first).isEqualTo(second);
        assertThat(first.overview()).isEqualTo("视频主要内容：first second third");
        assertThat(first.chapters()).extracting(VideoSummaryDraft.Chapter::startEvidenceId)
            .containsExactly("E0", "E2");
        assertThat(first.keyPoints()).extracting(VideoSummaryDraft.KeyPoint::content)
            .containsExactly("first", "second", "third");
        assertThat(first.keyPoints()).extracting(VideoSummaryDraft.KeyPoint::startEvidenceId)
            .containsExactly("E0", "E1", "E2");
    }
}
