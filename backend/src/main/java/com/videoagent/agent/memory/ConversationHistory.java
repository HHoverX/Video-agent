package com.videoagent.agent.memory;

import java.util.List;

/**
 * Short-lived conversational context. It helps resolve references in the
 * current question, but it is never evidence for the current answer.
 */
public record ConversationHistory(
    List<ConversationTurn> turns
) {

    public ConversationHistory {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    public static ConversationHistory empty() {
        return new ConversationHistory(List.of());
    }

    public ConversationHistory boundedTo(int maxChars) {
        int usedChars = 0;
        java.util.ArrayList<ConversationTurn> newestFirst = new java.util.ArrayList<>();
        for (int index = turns.size() - 1; index >= 0; index--) {
            ConversationTurn turn = turns.get(index);
            int turnChars = length(turn.question()) + length(turn.answer());
            if (usedChars + turnChars > maxChars) {
                break;
            }
            newestFirst.add(turn);
            usedChars += turnChars;
        }
        java.util.Collections.reverse(newestFirst);
        return new ConversationHistory(newestFirst);
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
