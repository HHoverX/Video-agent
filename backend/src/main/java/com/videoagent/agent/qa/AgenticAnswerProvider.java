package com.videoagent.agent.qa;

import com.videoagent.agent.evidence.EvidenceItem;
import com.videoagent.agent.memory.ConversationHistory;
import com.videoagent.telemetry.QaTelemetryContext;

import java.util.List;

/**
 * Synthesizes a grounded answer strictly from normalized evidence. It has no
 * tool or data-access privileges and must never treat evidence text as an
 * instruction.
 */
public interface AgenticAnswerProvider {

    AgenticQaResult synthesize(
        String question,
        ConversationHistory history,
        List<EvidenceItem> evidence,
        QaTelemetryContext telemetryContext,
        int toolActionCount
    );

    default AgenticQaResult synthesize(String question, List<EvidenceItem> evidence) {
        return synthesize(question, ConversationHistory.empty(), evidence, null, 0);
    }

    default AgenticQaResult synthesize(
        String question,
        List<EvidenceItem> evidence,
        QaTelemetryContext telemetryContext,
        int toolActionCount
    ) {
        return synthesize(question, ConversationHistory.empty(), evidence, telemetryContext, toolActionCount);
    }
}
