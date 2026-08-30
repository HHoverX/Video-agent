package com.videoagent.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;

class ConversationHistoryTest {

    @Test
    void shouldPreferRecentCompleteTurnsWithinCharacterBudget() {
        ConversationTurn oldest = new ConversationTurn("old-question", "old-answer");
        ConversationTurn middle = new ConversationTurn("middle-question", "middle-answer");
        ConversationTurn newest = new ConversationTurn("new-question", "new-answer");
        int budget = newest.question().length() + newest.answer().length()
            + middle.question().length() + middle.answer().length();

        ConversationHistory bounded = new ConversationHistory(List.of(oldest, middle, newest)).boundedTo(budget);

        assertThat(bounded.turns()).containsExactly(middle, newest);
    }

    @Test
    void shouldDropOlderTurnInsteadOfSplittingIt() {
        ConversationTurn old = new ConversationTurn("o".repeat(40), "a".repeat(40));
        ConversationTurn newest = new ConversationTurn("new", "answer");

        ConversationHistory bounded = new ConversationHistory(List.of(old, newest)).boundedTo(20);

        assertThat(bounded.turns()).containsExactly(newest);
    }
}
