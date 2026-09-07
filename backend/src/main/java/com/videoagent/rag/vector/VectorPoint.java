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
    float score,
    Long analysisTaskId
) {
    public VectorPoint {
        sourceSegmentIndexes = sourceSegmentIndexes == null
            ? List.of()
            : List.copyOf(sourceSegmentIndexes);
        vector = vector == null ? new float[0] : vector.clone();
    }

    public VectorPoint(int chunkIndex, String text, long startMs, long endMs,
                       List<Integer> sourceSegmentIndexes, float[] vector, float score) {
        this(chunkIndex, text, startMs, endMs, sourceSegmentIndexes, vector, score, null);
    }

    /** Constructor for search results that have no embedding vector attached. */
    public static VectorPoint retrieved(int chunkIndex, String text, long startMs, long endMs, float score) {
        return new VectorPoint(chunkIndex, text, startMs, endMs, List.of(), new float[0], score, 0L);
    }

    public static VectorPoint retrieved(long taskId, int chunkIndex, String text, long startMs, long endMs,
                                        List<Integer> sourceSegmentIndexes, float score) {
        return new VectorPoint(chunkIndex, text, startMs, endMs, sourceSegmentIndexes,
            new float[0], score, taskId);
    }
}
