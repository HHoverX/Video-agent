package com.videoagent.agent.memory;

/** Conversation storage scoped exclusively by user and video. */
public interface ConversationMemory {

    ConversationHistory load(long userId, long videoId, String requestId);

    void appendTurn(long userId, long videoId, ConversationTurn turn, String requestId);

    default ConversationHistory load(long userId, long videoId) {
        return load(userId, videoId, null);
    }

    default void appendTurn(long userId, long videoId, ConversationTurn turn) {
        appendTurn(userId, videoId, turn, null);
    }
}
