package com.videoagent.rag.chunk;

import com.videoagent.rag.config.RagProperties;
import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;

import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(TranscriptChunker.class);

    private final RagProperties properties;
    private final TokenEstimator tokenEstimator;

    public TranscriptChunker(RagProperties properties, TokenEstimator tokenEstimator) {
        this.properties = properties;
        this.tokenEstimator = tokenEstimator;
    }

    public List<TranscriptChunk> chunk(List<VideoTranscriptSegmentEntity> segments) {
        List<VideoTranscriptSegmentEntity> ordered = segments.stream()
            .filter(segment -> segment.getText() != null && !segment.getText().isBlank())
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
                int estimatedTokens = tokenEstimator.estimateTokens(builder.textWith(segment));
                if (builder.isEmpty() || estimatedTokens <= properties.chunkTargetTokens()) {
                    builder.add(segment);
                    if (builder.size() == 1 && estimatedTokens > properties.chunkTargetTokens()) {
                        log.warn("[segmentIndex={}][estimatedTokens={}][chunkTargetTokens={}] transcript segment exceeds chunk target and remains atomic",
                            segment.getSegmentIndex(), estimatedTokens, properties.chunkTargetTokens());
                    }
                    end++;
                } else {
                    break;
                }
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
        private final StringBuilder text = new StringBuilder();

        private ChunkBuilder(int chunkIndex) {
            this.chunkIndex = chunkIndex;
        }

        private String textWith(VideoTranscriptSegmentEntity segment) {
            if (text.isEmpty()) {
                return segment.getText();
            }
            return text + "\n" + segment.getText();
        }

        private void add(VideoTranscriptSegmentEntity segment) {
            segments.add(segment);
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(segment.getText());
            if (segment.getSegmentIndex() != null) {
                sourceSegmentIndexes.add(segment.getSegmentIndex());
            }
        }

        private boolean isEmpty() {
            return segments.isEmpty();
        }

        private int size() {
            return segments.size();
        }

        private TranscriptChunk build() {
            VideoTranscriptSegmentEntity first = segments.getFirst();
            VideoTranscriptSegmentEntity last = segments.getLast();
            return new TranscriptChunk(
                chunkIndex,
                text.toString(),
                first.getStartMs() == null ? 0L : first.getStartMs(),
                last.getEndMs() == null ? first.getStartMs() : last.getEndMs(),
                List.copyOf(sourceSegmentIndexes)
            );
        }
    }
}
