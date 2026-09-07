package com.videoagent.agent.memory;

/** Conversation storage scoped exclusively by user and video. */
public interface ConversationMemory {

    ConversationHistory load(long userId, long videoId);

    void appendTurn(long userId, long videoId, ConversationTurn turn);
}
