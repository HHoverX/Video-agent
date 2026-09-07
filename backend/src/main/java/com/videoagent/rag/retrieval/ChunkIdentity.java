package com.videoagent.rag.retrieval;

public final class ChunkIdentity {
    private ChunkIdentity() {
    }

    public static String of(long videoId, long analysisTaskId, int chunkIndex) {
        return videoId + ":" + analysisTaskId + ":" + chunkIndex;
    }
}
