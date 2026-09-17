package com.videoagent.agent.memory;

import java.util.List;

/**
 * Short-lived conversational context. It helps resolve references in the
 * current question, but it is never evidence for the current answer.
 */
public record ConversationHistory(
    String summary,
    List<ConversationTurn> recentTurns
) {

    public ConversationHistory {
        summary = summary == null ? "" : summary;
        recentTurns = recentTurns == null ? List.of() : List.copyOf(recentTurns);
    }

    public ConversationHistory(List<ConversationTurn> recentTurns) {
        this("", recentTurns);
    }

    public static ConversationHistory empty() {
        return new ConversationHistory("", List.of());
    }

    public ConversationHistory boundedTo(int maxChars) {
        int usedChars = 0;
        java.util.ArrayList<ConversationTurn> newestFirst = new java.util.ArrayList<>();
        for (int index = recentTurns.size() - 1; index >= 0; index--) {
            ConversationTurn turn = recentTurns.get(index);
            int turnChars = length(turn.question()) + length(turn.answer());
            if (usedChars + turnChars > maxChars) {
                if (newestFirst.isEmpty() && maxChars > 0) {
                    newestFirst.add(truncate(turn, maxChars));
                    usedChars = maxChars;
                }
                break;
            }
            newestFirst.add(turn);
            usedChars += turnChars;
        }
        java.util.Collections.reverse(newestFirst);
        int summaryBudget = Math.max(0, maxChars - usedChars);
        String boundedSummary = summary.length() <= summaryBudget
            ? summary
            : summary.substring(summary.length() - summaryBudget);
        return new ConversationHistory(boundedSummary, newestFirst);
    }

    private static ConversationTurn truncate(ConversationTurn turn, int maxChars) {
        String question = turn.question() == null ? "" : turn.question();
        String answer = turn.answer() == null ? "" : turn.answer();
        int questionChars = Math.min(question.length(), maxChars);
        String boundedQuestion = question.substring(0, questionChars);
        int answerChars = Math.min(answer.length(), maxChars - questionChars);
        return new ConversationTurn(boundedQuestion, answer.substring(0, answerChars));
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
