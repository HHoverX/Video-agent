package com.videoagent.agent.context;

import com.videoagent.rag.context.QaContextMode;

/**
 * Server-bound QA context. Built from the authenticated request (current user)
 * and the route videoId after ownership is verified. Tools execute exclusively
 * inside this bound context: the LLM can never influence userId or videoId.
 */
public record AgenticQaContext(
    long currentUserId,
    long videoId,
    Long analysisTaskId,
    QaContextMode contextMode,
    boolean hasTranscript,
    boolean hasSummary,
    String ragStatus
) {

    public boolean ragReady() {
        return "READY".equals(ragStatus);
    }
}
