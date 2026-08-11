package com.videoagent.agent.plan;

import java.util.List;

/**
 * The planner's structured output. The LLM only decides intent and which tools
 * to invoke; it never sees or controls user/video identity.
 */
public record RetrievalPlan(
    String intent,
    String strategyLabel,
    List<RetrievalAction> actions
) {
    public RetrievalPlan {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }
}
