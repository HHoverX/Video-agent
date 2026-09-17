package com.videoagent.agent.memory.summary;

import com.videoagent.agent.memory.ConversationTurn;

import java.util.List;

public class MockConversationSummaryProvider implements ConversationSummaryProvider {

    @Override
    public String summarize(
        String oldSummary,
        List<ConversationTurn> turnsToCompact,
        int maxSummaryChars
    ) {
        StringBuilder summary = new StringBuilder(oldSummary == null ? "" : oldSummary.strip());
        for (ConversationTurn turn : turnsToCompact == null ? List.<ConversationTurn>of() : turnsToCompact) {
            append(summary, "用户询问：", turn.question());
            append(summary, "回答涉及：", turn.answer());
        }
        if (summary.length() > maxSummaryChars) {
            return summary.substring(summary.length() - maxSummaryChars);
        }
        return summary.toString();
    }

    private void append(StringBuilder target, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append('；');
        }
        target.append(label).append(value.strip());
    }
}
