package com.videoagent.summary.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.asr.TranscriptSegment;
import com.videoagent.summary.service.SummaryResultValidator;

import org.junit.jupiter.api.Test;

import java.util.List;

class MockVideoSummaryProviderTest {

    private final MockVideoSummaryProvider provider =
        new MockVideoSummaryProvider(new SummaryResultValidator());

    @Test
    void shouldReturnDeterministicTimestampedStructuredResult() {
        VideoSummaryRequest request = new VideoSummaryRequest(7L, 11L, List.of(
            new TranscriptSegment(4_000, 6_000, "third"),
            new TranscriptSegment(0, 2_000, "first"),
            new TranscriptSegment(2_000, 4_000, "second")
        ));

        VideoSummaryResult first = provider.summarize(request);
        VideoSummaryResult second = provider.summarize(request);

        assertThat(first).isEqualTo(second);
        assertThat(first.overview()).isEqualTo("视频主要内容：first second third");
        assertThat(first.chapters()).extracting(SummaryChapter::startMs)
            .containsExactly(0L, 4_000L);
        assertThat(first.keyPoints()).extracting(SummaryKeyPoint::content)
            .containsExactly("first", "second", "third");
        assertThat(first.keyPoints()).extracting(SummaryKeyPoint::startMs)
            .containsExactly(0L, 2_000L, 4_000L);
    }
}
