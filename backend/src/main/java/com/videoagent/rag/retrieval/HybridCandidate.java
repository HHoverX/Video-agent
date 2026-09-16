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
    double rrfScore
) {
    public HybridCandidate {
        sourceSegmentIndexes = sourceSegmentIndexes == null ? List.of() : List.copyOf(sourceSegmentIndexes);
    }
}
