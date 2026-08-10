package com.videoagent.rag.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.rag.config.RagProperties;
import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class TranscriptChunkerTest {

    private final RagProperties properties = new RagProperties(1000, 200, 1, 5);
    private final TranscriptChunker chunker = new TranscriptChunker(properties);

    @Test
    void shouldChunkSingleSegment() {
        List<TranscriptChunk> chunks = chunker.chunk(List.of(
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
    void shouldAggregateMultipleAdjacentSegments() {
        List<TranscriptChunk> chunks = chunker.chunk(List.of(
            segment(0, 0, 2000, "first"),
            segment(1, 2000, 4000, "second"),
            segment(2, 4000, 6000, "third")
        ));
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().sourceSegmentIndexes()).containsExactly(0, 1, 2);
        assertThat(chunks.getFirst().startMs()).isZero();
        assertThat(chunks.getFirst().endMs()).isEqualTo(6000L);
        assertThat(chunks.getFirst().text()).isEqualTo("first\nsecond\nthird");
    }

    @Test
    void shouldSplitWhenMaxCharsReached() {
        // Each segment is 150 chars; max is 200 -> first chunk takes one,
        // second chunk takes the next.
        List<VideoTranscriptSegmentEntity> segments = List.of(
            segment(0, 0, 2000, "a".repeat(150)),
            segment(1, 2000, 4000, "b".repeat(150)),
            segment(2, 4000, 6000, "c".repeat(150))
        );
        List<TranscriptChunk> chunks = chunker.chunk(segments);
        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks.getFirst().sourceSegmentIndexes()).containsExactly(0);
        assertThat(chunks.get(1).sourceSegmentIndexes()).containsExactly(1);
    }

    @Test
    void shouldApplyOverlap() {
        // max=200, each segment 80 chars: two fit (160) but three do not (240).
        // chunk1=[0,1]; overlap by 1 -> chunk2 starts at 1 -> [1,2]; chunk3=[2,3].
        List<VideoTranscriptSegmentEntity> segments = List.of(
            segment(0, 0, 1000, "a".repeat(80)),
            segment(1, 1000, 2000, "b".repeat(80)),
            segment(2, 2000, 3000, "c".repeat(80)),
            segment(3, 3000, 4000, "d".repeat(80))
        );
        List<TranscriptChunk> chunks = chunker.chunk(segments);
        assertThat(chunks).hasSize(3);
        assertThat(chunks.getFirst().sourceSegmentIndexes()).containsExactly(0, 1);
        assertThat(chunks.get(1).sourceSegmentIndexes()).containsExactly(1, 2);
        assertThat(chunks.get(2).sourceSegmentIndexes()).containsExactly(2, 3);
    }

    @Test
    void shouldPreserveTimestampOrdering() {
        // Deliberately unsorted input; output must be time-ordered.
        List<VideoTranscriptSegmentEntity> shuffled = new ArrayList<>(List.of(
            segment(0, 0, 1000, "zero"),
            segment(2, 4000, 6000, "two"),
            segment(1, 1000, 4000, "one")
        ));
        List<TranscriptChunk> chunks = chunker.chunk(shuffled);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().startMs()).isZero();
        assertThat(chunks.getFirst().endMs()).isEqualTo(6000L);
        assertThat(chunks.getFirst().sourceSegmentIndexes()).containsExactly(0, 1, 2);
    }

    @Test
    void shouldHandleEmptyTranscript() {
        assertThat(chunker.chunk(List.of())).isEmpty();
    }

    @Test
    void shouldHandleSingleSegmentExceedingMaxChars() {
        VideoTranscriptSegmentEntity giant = segment(0, 0, 5000, "g".repeat(5000));
        List<TranscriptChunk> chunks = chunker.chunk(List.of(giant));
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().text()).hasSize(5000);
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
