package com.videoagent.agent.memory;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class ConversationTurnStore {

    private final ConversationTurnRepository repository;

    public ConversationTurnStore(ConversationTurnRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void append(long userId, long videoId, ConversationTurn turn) {
        ConversationTurnEntity entity = new ConversationTurnEntity();
        entity.setUserId(userId);
        entity.setVideoId(videoId);
        entity.setQuestion(turn.question());
        entity.setAnswer(turn.answer());
        entity.setCreatedAt(LocalDateTime.now());
        try {
            if (repository.insert(entity) != 1) {
                throw new VideoAgentException(ErrorCode.INTERNAL_ERROR, "会话历史保存失败");
            }
        } catch (DataAccessException exception) {
            throw new VideoAgentException(ErrorCode.INTERNAL_ERROR, "会话历史保存失败", exception);
        }
    }

    @Transactional(readOnly = true)
    public ConversationHistory loadRecent(long userId, long videoId, int limit) {
        List<ConversationTurnEntity> newestFirst = repository.findRecent(userId, videoId, limit);
        List<ConversationTurn> oldestFirst = new ArrayList<>(newestFirst.size());
        for (ConversationTurnEntity entity : newestFirst) {
            oldestFirst.add(new ConversationTurn(entity.getQuestion(), entity.getAnswer()));
        }
        Collections.reverse(oldestFirst);
        return new ConversationHistory(oldestFirst);
    }
}
