package com.videoagent.agent.dto;

import java.util.List;

/**
 * Response of the agentic QA endpoint. The strategy label tells the frontend
 * which retrieval approach was used. Planner hidden reasoning is never
 * exposed.
 */
public record AgenticQaResponse(
    String answer,
    String strategy,
    String contextMode,
    List<String> toolsUsed,
    List<AgenticCitation> citations
) {
}
