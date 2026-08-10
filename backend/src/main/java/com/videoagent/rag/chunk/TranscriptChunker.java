package com.videoagent.rag.chunk;

import com.videoagent.rag.config.RagProperties;
import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Splits an ordered transcript into time-anchored chunks. Only adjacent
 * transcript segments may be combined, time order is preserved, and each chunk
 * carries its own startMs/endMs plus the source segment indexes it was built
 * from, so retrieval citations can be traced back to real transcript rows.
 */
@Component
public class TranscriptChunker {

    private final RagProperties properties;

    public TranscriptChunker(RagProperties properties) {
        this.properties = properties;
    }

    public List<TranscriptChunk> chunk(List<VideoTranscriptSegmentEntity> segments) {
        List<VideoTranscriptSegmentEntity> ordered = segments.stream()
            .sorted(Comparator
                .comparing(VideoTranscriptSegmentEntity::getSegmentIndex,
                    Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(VideoTranscriptSegmentEntity::getStartMs,
                    Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
        if (ordered.isEmpty()) {
            return List.of();
        }

        List<TranscriptChunk> chunks = new ArrayList<>();
        int chunkIndex = 0;
        int cursor = 0;
        while (cursor < ordered.size()) {
            ChunkBuilder builder = new ChunkBuilder(chunkIndex);
            int end = cursor;
            while (end < ordered.size()) {
                VideoTranscriptSegmentEntity segment = ordered.get(end);
                if (builder.canAdd(segment, properties.chunkMaxChars())) {
                    builder.add(segment);
                    end++;
                } else {
                    break;
                }
            }
            // At least one segment is always in a chunk even if a single
            // segment exceeds the max char budget.
            if (builder.isEmpty() && end < ordered.size()) {
                builder.add(ordered.get(end));
                end++;
            }
            chunks.add(builder.build());
            chunkIndex++;
            int overlap = properties.chunkOverlapSegments();
            // Guarantee strict forward progress: always advance past the
            // current chunk start, and only step back into the overlap window
            // when there are remaining segments to consume.
            int nextCursor = Math.max(cursor + 1, end - overlap);
            if (end >= ordered.size()) {
                nextCursor = ordered.size();
            }
            cursor = nextCursor;
        }
        return chunks;
    }

    private static final class ChunkBuilder {
        private final int chunkIndex;
        private final List<VideoTranscriptSegmentEntity> segments = new ArrayList<>();
        private final Set<Integer> sourceSegmentIndexes = new LinkedHashSet<>();
        private long textLength = 0;

        private ChunkBuilder(int chunkIndex) {
            this.chunkIndex = chunkIndex;
        }

        private boolean canAdd(VideoTranscriptSegmentEntity segment, int maxChars) {
            int textLength = segment.getText() == null ? 0 : segment.getText().length();
            return this.textLength + textLength <= maxChars;
        }

        private void add(VideoTranscriptSegmentEntity segment) {
            segments.add(segment);
            if (segment.getText() != null) {
                textLength += segment.getText().length();
            }
            if (segment.getSegmentIndex() != null) {
                sourceSegmentIndexes.add(segment.getSegmentIndex());
            }
        }

        private boolean isEmpty() {
            return segments.isEmpty();
        }

        private TranscriptChunk build() {
            VideoTranscriptSegmentEntity first = segments.getFirst();
            VideoTranscriptSegmentEntity last = segments.getLast();
            String text = segments.stream()
                .map(s -> s.getText() == null ? "" : s.getText())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
            return new TranscriptChunk(
                chunkIndex,
                text,
                first.getStartMs() == null ? 0L : first.getStartMs(),
                last.getEndMs() == null ? first.getStartMs() : last.getEndMs(),
                List.copyOf(sourceSegmentIndexes)
            );
        }
    }
}
