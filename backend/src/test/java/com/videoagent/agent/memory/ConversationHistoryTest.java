package com.videoagent.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;

class ConversationHistoryTest {

    @Test
    void shouldPreferRecentCompleteTurnsThenUseRemainingBudgetForSummary() {
        ConversationTurn oldest = new ConversationTurn("old-question", "old-answer");
        ConversationTurn newest = new ConversationTurn("new-question", "new-answer");
        int newestChars = newest.question().length() + newest.answer().length();

        ConversationHistory bounded = new ConversationHistory(
            "0123456789",
            List.of(oldest, newest)
        ).boundedTo(newestChars + 4);

        assertThat(bounded.recentTurns()).containsExactly(newest);
        assertThat(bounded.summary()).isEqualTo("6789");
    }

    @Test
    void shouldTruncateSingleOversizedNewestTurnWithoutChangingStoredHistory() {
        ConversationTurn newest = new ConversationTurn("question", "answer");
        ConversationHistory original = new ConversationHistory("summary", List.of(newest));

        ConversationHistory bounded = original.boundedTo(10);

        assertThat(bounded.summary()).isEmpty();
        assertThat(bounded.recentTurns()).containsExactly(new ConversationTurn("question", "an"));
        assertThat(original.recentTurns()).containsExactly(newest);
    }
}
