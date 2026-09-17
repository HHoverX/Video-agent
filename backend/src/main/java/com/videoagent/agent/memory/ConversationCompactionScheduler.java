package com.videoagent.agent.memory;

public interface ConversationCompactionScheduler {

    void schedule(long userId, long videoId);
}
