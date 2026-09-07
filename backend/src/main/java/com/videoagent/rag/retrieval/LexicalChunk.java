package com.videoagent.rag.retrieval;

import java.util.List;

public record LexicalChunk(
    String chunkId,
    int chunkIndex,
    String text,
    long startMs,
    long endMs,
    List<Integer> sourceSegmentIndexes,
    double lexicalScore
) {
    public LexicalChunk {
        sourceSegmentIndexes = sourceSegmentIndexes == null ? List.of() : List.copyOf(sourceSegmentIndexes);
    }
}
