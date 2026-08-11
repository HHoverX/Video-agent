package com.videoagent.agent.plan;

/**
 * The only tools an agent may invoke. Deliberately closed: the LLM cannot name
 * an arbitrary tool, and no action carries a userId or videoId — those come
 * exclusively from the authenticated request's server-bound context.
 */
public enum RetrievalTool {
    GET_VIDEO_SUMMARY,
    GET_TRANSCRIPT_BY_TIME,
    SEARCH_TRANSCRIPT
}
