package com.videoagent.rag.retrieval;

import com.videoagent.rag.vector.VectorPoint;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ReciprocalRankFusion {

    public List<HybridCandidate> fuse(long videoId, List<VectorPoint> dense, List<LexicalChunk> lexical, int k) {
        Map<String, MutableCandidate> candidates = new LinkedHashMap<>();
        for (int index = 0; index < dense.size(); index++) {
            VectorPoint hit = dense.get(index);
            String id = ChunkIdentity.of(videoId, hit.analysisTaskId(), hit.chunkIndex());
            candidates.computeIfAbsent(id, ignored -> MutableCandidate.fromDense(id, hit))
                .addDense(hit.score(), reciprocal(k, index + 1));
        }
        for (int index = 0; index < lexical.size(); index++) {
            LexicalChunk hit = lexical.get(index);
            candidates.computeIfAbsent(hit.chunkId(), ignored -> MutableCandidate.fromLexical(hit))
                .addLexical(hit.lexicalScore(), reciprocal(k, index + 1));
        }
        return candidates.values().stream()
            .map(MutableCandidate::toImmutable)
            .sorted(Comparator.comparingDouble(HybridCandidate::rrfScore).reversed()
                .thenComparing(HybridCandidate::chunkId))
            .toList();
    }

    private double reciprocal(int k, int rank) {
        return 1.0 / (k + rank);
    }

    private static final class MutableCandidate {
        private final String id;
        private final int index;
        private final String text;
        private final long startMs;
        private final long endMs;
        private final List<Integer> segmentIndexes;
        private Float denseScore;
        private Double lexicalScore;
        private double rrfScore;

        private MutableCandidate(String id, int index, String text, long startMs, long endMs,
                                 List<Integer> segmentIndexes) {
            this.id = id;
            this.index = index;
            this.text = text;
            this.startMs = startMs;
            this.endMs = endMs;
            this.segmentIndexes = new ArrayList<>(segmentIndexes);
        }

        static MutableCandidate fromDense(String id, VectorPoint hit) {
            return new MutableCandidate(id, hit.chunkIndex(), hit.text(), hit.startMs(), hit.endMs(),
                hit.sourceSegmentIndexes());
        }

        static MutableCandidate fromLexical(LexicalChunk hit) {
            return new MutableCandidate(hit.chunkId(), hit.chunkIndex(), hit.text(), hit.startMs(), hit.endMs(),
                hit.sourceSegmentIndexes());
        }

        MutableCandidate addDense(float score, double reciprocal) {
            denseScore = score;
            rrfScore += reciprocal;
            return this;
        }

        MutableCandidate addLexical(double score, double reciprocal) {
            lexicalScore = score;
            rrfScore += reciprocal;
            return this;
        }

        HybridCandidate toImmutable() {
            return new HybridCandidate(id, index, text, startMs, endMs, segmentIndexes,
                denseScore, lexicalScore, rrfScore);
        }
    }
}
