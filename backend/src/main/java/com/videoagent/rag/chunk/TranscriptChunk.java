package com.videoagent.rag.chunk;

import java.util.List;

public record TranscriptChunk(
    int chunkIndex,
    String text,
    long startMs,
    long endMs,
    List<Integer> sourceSegmentIndexes
) {
    public TranscriptChunk {
        sourceSegmentIndexes = sourceSegmentIndexes == null
            ? List.of()
            : List.copyOf(sourceSegmentIndexes);
    }
}
