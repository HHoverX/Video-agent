package com.videoagent.agent.memory;

import com.videoagent.agent.config.ConversationMemoryProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class PersistentConversationMemory implements ConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(PersistentConversationMemory.class);

    private final RedisConversationMemory redisMemory;
    private final ConversationTurnStore turnStore;
    private final ConversationMemoryProperties properties;

    public PersistentConversationMemory(
        RedisConversationMemory redisMemory,
        ConversationTurnStore turnStore,
        ConversationMemoryProperties properties
    ) {
        this.redisMemory = redisMemory;
        this.turnStore = turnStore;
        this.properties = properties;
    }

    @Override
    public ConversationHistory load(long userId, long videoId) {
        Optional<ConversationHistory> cached;
        try {
            cached = redisMemory.load(userId, videoId);
        } catch (DataAccessException exception) {
            log.warn("[userId={}][videoId={}][exceptionClass={}] conversation cache read failed; falling back to MySQL",
                userId, videoId, exception.getClass().getSimpleName());
            cached = Optional.empty();
        }
        if (cached.isPresent()) {
            return cached.get();
        }

        ConversationHistory history = turnStore.loadRecent(userId, videoId, properties.maxTurns());
        refillBestEffort(userId, videoId, history);
        return history;
    }

    @Override
    public void appendTurn(long userId, long videoId, ConversationTurn turn) {
        turnStore.append(userId, videoId, turn);

        ConversationHistory recent;
        try {
            recent = turnStore.loadRecent(userId, videoId, properties.maxTurns());
        } catch (DataAccessException exception) {
            log.warn("[userId={}][videoId={}][exceptionClass={}] conversation cache refresh preparation failed; turn remains persisted",
                userId, videoId, exception.getClass().getSimpleName());
            return;
        }
        refillBestEffort(userId, videoId, recent);
    }

    private void refillBestEffort(long userId, long videoId, ConversationHistory history) {
        try {
            redisMemory.replace(userId, videoId, history);
        } catch (DataAccessException exception) {
            log.warn("[userId={}][videoId={}][exceptionClass={}] conversation cache update failed; MySQL history remains authoritative",
                userId, videoId, exception.getClass().getSimpleName());
        }
    }
}
