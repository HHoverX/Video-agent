package com.videoagent.agent.memory.summary;

import com.videoagent.agent.memory.ConversationTurn;

import java.util.List;

public interface ConversationSummaryProvider {

    String summarize(String oldSummary, List<ConversationTurn> turnsToCompact, int maxSummaryChars);
}
