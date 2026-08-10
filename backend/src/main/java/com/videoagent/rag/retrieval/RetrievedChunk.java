package com.videoagent.rag.retrieval;

import java.util.List;

public record RetrievedChunk(
    int chunkIndex,
    String text,
    long startMs,
    long endMs,
    List<Integer> sourceSegmentIndexes,
    float score
) {
    public RetrievedChunk {
        sourceSegmentIndexes = sourceSegmentIndexes == null
            ? List.of()
            : List.copyOf(sourceSegmentIndexes);
    }
}
