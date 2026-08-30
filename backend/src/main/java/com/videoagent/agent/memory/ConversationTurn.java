package com.videoagent.agent.memory;

/** A completed user question and the final answer returned to the user. */
public record ConversationTurn(
    String question,
    String answer
) {
}
