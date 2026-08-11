package com.videoagent.agent.plan;

import java.util.List;

/**
 * A single planned tool invocation. The tool enum is closed and no field here
 * can influence which user's or which video's data is accessed — that is bound
 * server-side from the authenticated request.
 */
public record RetrievalAction(
    RetrievalTool tool,
    String query,
    Long timeMs,
    Long windowMs
) {

    public static RetrievalAction summary() {
        return new RetrievalAction(RetrievalTool.GET_VIDEO_SUMMARY, null, null, null);
    }

    public static RetrievalAction byTime(long timeMs, long windowMs) {
        return new RetrievalAction(RetrievalTool.GET_TRANSCRIPT_BY_TIME, null, timeMs, windowMs);
    }

    public static RetrievalAction search(String query) {
        return new RetrievalAction(RetrievalTool.SEARCH_TRANSCRIPT, query, null, null);
    }
}
