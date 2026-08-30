package com.videoagent.agent.planner;

import com.videoagent.agent.context.AgenticQaContext;
import com.videoagent.agent.memory.ConversationHistory;
import com.videoagent.agent.plan.RetrievalPlan;
import com.videoagent.telemetry.QaTelemetryContext;

/**
 * Decides which retrieval tools to invoke for a question. It only plans "what
 * to look up"; it never answers and never sees the full transcript. The output
 * is a strict structured plan that the backend validates before any tool runs.
 */
public interface RetrievalPlannerProvider {

    RetrievalPlan plan(
        AgenticQaContext context,
        String question,
        ConversationHistory history,
        QaTelemetryContext telemetryContext
    );

    default RetrievalPlan plan(AgenticQaContext context, String question) {
        return plan(context, question, ConversationHistory.empty(), null);
    }

    default RetrievalPlan plan(
        AgenticQaContext context,
        String question,
        QaTelemetryContext telemetryContext
    ) {
        return plan(context, question, ConversationHistory.empty(), telemetryContext);
    }
}
