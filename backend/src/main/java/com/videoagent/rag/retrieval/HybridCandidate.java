package com.videoagent.rag.retrieval;

import java.util.List;

public record HybridCandidate(
    String chunkId,
    int chunkIndex,
    String text,
    long startMs,
    long endMs,
    List<Integer> sourceSegmentIndexes,
    Float denseScore,
    Double lexicalScore,
    double rrfScore,
    Double rerankScore
) {
    public HybridCandidate {
        sourceSegmentIndexes = sourceSegmentIndexes == null ? List.of() : List.copyOf(sourceSegmentIndexes);
    }

    public HybridCandidate withRerankScore(double score) {
        return new HybridCandidate(chunkId, chunkIndex, text, startMs, endMs, sourceSegmentIndexes,
            denseScore, lexicalScore, rrfScore, score);
    }
}
