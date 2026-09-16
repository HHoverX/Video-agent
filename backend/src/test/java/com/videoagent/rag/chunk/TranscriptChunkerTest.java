package com.videoagent.rag.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.rag.config.RagProperties;
import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class TranscriptChunkerTest {

    @Test
    void shouldMergeSegmentsUntilCandidateExceedsTarget() {
        TokenEstimator estimator = text -> switch (text) {
            case "S1" -> 100;
            case "S1\nS2" -> 280;
            case "S1\nS2\nS3" -> 480;
            case "S1\nS2\nS3\nS4" -> 730;
            case "S3\nS4" -> 450;
            default -> 250;
        };

        List<TranscriptChunk> chunks = chunker(600, 1, estimator).chunk(List.of(
            segment(0, 0, 1000, "S1"),
            segment(1, 1000, 2000, "S2"),
            segment(2, 2000, 3000, "S3"),
            segment(3, 3000, 4000, "S4")
        ));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst().sourceSegmentIndexes()).containsExactly(0, 1, 2);
        assertThat(chunks.getFirst().text()).isEqualTo("S1\nS2\nS3");
        assertThat(chunks.get(1).sourceSegmentIndexes()).containsExactly(2, 3);
    }

    @Test
    void shouldOverlapByOneWholeSegment() {
        List<TranscriptChunk> chunks = chunker(3, 1, String::length).chunk(List.of(
            segment(0, 0, 1000, "a"),
            segment(1, 1000, 2000, "b"),
            segment(2, 2000, 3000, "c"),
            segment(3, 3000, 4000, "d")
        ));

        assertThat(chunks).hasSize(3);
        assertThat(chunks.getFirst().sourceSegmentIndexes()).containsExactly(0, 1);
        assertThat(chunks.get(1).sourceSegmentIndexes()).containsExactly(1, 2);
        assertThat(chunks.get(2).sourceSegmentIndexes()).containsExactly(2, 3);
    }

    @Test
    void shouldKeepOversizedSegmentAtomic() {
        List<TranscriptChunk> chunks = chunker(600, 1, text -> 800).chunk(List.of(
            segment(7, 1000, 5000, "oversized segment")
        ));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().text()).isEqualTo("oversized segment");
        assertThat(chunks.getFirst().sourceSegmentIndexes()).containsExactly(7);
    }

    @Test
    void shouldReturnFinalChunkBelowTarget() {
        TokenEstimator estimator = text -> switch (text) {
            case "S1", "S2" -> 250;
            case "S3" -> 100;
            case "S1\nS2" -> 500;
            case "S1\nS2\nS3" -> 700;
            default -> 700;
        };

        List<TranscriptChunk> chunks = chunker(600, 0, estimator).chunk(List.of(
            segment(0, 0, 1000, "S1"),
            segment(1, 1000, 2000, "S2"),
            segment(2, 2000, 3000, "S3")
        ));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).text()).isEqualTo("S3");
    }

    @Test
    void shouldChunkSingleSegment() {
        List<TranscriptChunk> chunks = chunker(600, 1, text -> 10).chunk(List.of(
            segment(0, 0, 5000, "only segment")
        ));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().chunkIndex()).isZero();
        assertThat(chunks.getFirst().text()).isEqualTo("only segment");
        assertThat(chunks.getFirst().startMs()).isZero();
        assertThat(chunks.getFirst().endMs()).isEqualTo(5000L);
        assertThat(chunks.getFirst().sourceSegmentIndexes()).containsExactly(0);
    }

    @Test
    void shouldHandleEmptyTranscript() {
        assertThat(chunker(600, 1, text -> 1).chunk(List.of())).isEmpty();
    }

    @Test
    void shouldAlwaysAdvanceWhenOverlapExceedsChunkSize() {
        List<TranscriptChunk> chunks = chunker(1, 99, String::length).chunk(List.of(
            segment(0, 0, 1000, "a"),
            segment(1, 1000, 2000, "b"),
            segment(2, 2000, 3000, "c")
        ));

        assertThat(chunks).hasSize(3);
        assertThat(chunks).extracting(TranscriptChunk::sourceSegmentIndexes)
            .containsExactly(List.of(0), List.of(1), List.of(2));
    }

    @Test
    void shouldPreserveTimestampAndSourceIndexesAfterSorting() {
        List<VideoTranscriptSegmentEntity> shuffled = new ArrayList<>(List.of(
            segment(0, 0, 1000, "zero"),
            segment(2, 4000, 6000, "two"),
            segment(1, 1000, 4000, "one")
        ));

        List<TranscriptChunk> chunks = chunker(600, 1, text -> 10).chunk(shuffled);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().startMs()).isZero();
        assertThat(chunks.getFirst().endMs()).isEqualTo(6000L);
        assertThat(chunks.getFirst().sourceSegmentIndexes()).containsExactly(0, 1, 2);
        assertThat(chunks.getFirst().text()).isEqualTo("zero\none\ntwo");
    }

    @Test
    void shouldEstimateTheFinalNewlineJoinedText() {
        List<String> estimatedTexts = new ArrayList<>();
        TokenEstimator estimator = text -> {
            estimatedTexts.add(text);
            return 1;
        };

        chunker(600, 1, estimator).chunk(List.of(
            segment(0, 0, 1000, "first"),
            segment(1, 1000, 2000, "second")
        ));

        assertThat(estimatedTexts).containsExactly("first", "first\nsecond");
    }

    @Test
    void shouldSkipBlankSegmentsInsteadOfCreatingEmptyChunks() {
        List<TranscriptChunk> chunks = chunker(600, 1, text -> 1).chunk(List.of(
            segment(0, 0, 1000, " "),
            segment(1, 1000, 2000, "kept"),
            segment(2, 2000, 3000, "")
        ));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().text()).isEqualTo("kept");
        assertThat(chunks.getFirst().sourceSegmentIndexes()).containsExactly(1);
    }

    private TranscriptChunker chunker(int targetTokens, int overlapSegments, TokenEstimator estimator) {
        return new TranscriptChunker(
            new RagProperties(targetTokens, overlapSegments, 5, 0.0f),
            estimator
        );
    }

    private VideoTranscriptSegmentEntity segment(int index, long startMs, long endMs, String text) {
        VideoTranscriptSegmentEntity entity = new VideoTranscriptSegmentEntity();
        entity.setSegmentIndex(index);
        entity.setStartMs(startMs);
        entity.setEndMs(endMs);
        entity.setText(text);
        return entity;
    }
}
