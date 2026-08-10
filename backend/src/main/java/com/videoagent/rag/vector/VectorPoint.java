package com.videoagent.rag.vector;

import java.util.List;

/**
 * A vector store point (before upsert) or a retrieval hit (with a score).
 * Text plus time range come from the real transcript chunks; citations are
 * always derived from this persisted metadata, never from the LLM.
 */
public record VectorPoint(
    int chunkIndex,
    String text,
    long startMs,
    long endMs,
    List<Integer> sourceSegmentIndexes,
    float[] vector,
    float score
) {
    public VectorPoint {
        sourceSegmentIndexes = sourceSegmentIndexes == null
            ? List.of()
            : List.copyOf(sourceSegmentIndexes);
        vector = vector == null ? new float[0] : vector.clone();
    }

    /** Constructor for search results that have no embedding vector attached. */
    public static VectorPoint retrieved(int chunkIndex, String text, long startMs, long endMs, float score) {
        return new VectorPoint(chunkIndex, text, startMs, endMs, List.of(), new float[0], score);
    }
}
