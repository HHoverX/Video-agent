package com.videoagent.agent.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

class ConversationTurnStoreTest {

    private final ConversationTurnRepository repository = mock(ConversationTurnRepository.class);
    private final ConversationTurnStore store = new ConversationTurnStore(repository);

    @Test
    void shouldPersistUserVideoQuestionAndAnswer() {
        when(repository.insert(org.mockito.ArgumentMatchers.any(ConversationTurnEntity.class))).thenReturn(1);

        store.append(1L, 7L, new ConversationTurn("question", "answer"));

        var entity = org.mockito.ArgumentCaptor.forClass(ConversationTurnEntity.class);
        verify(repository).insert(entity.capture());
        assertThat(entity.getValue().getUserId()).isEqualTo(1L);
        assertThat(entity.getValue().getVideoId()).isEqualTo(7L);
        assertThat(entity.getValue().getQuestion()).isEqualTo("question");
        assertThat(entity.getValue().getAnswer()).isEqualTo("answer");
        assertThat(entity.getValue().getCreatedAt()).isNotNull();
    }

    @Test
    void shouldReverseDescendingDatabaseRowsForAgentConsumption() {
        when(repository.findRecent(1L, 7L, 2)).thenReturn(List.of(
            entity(3L, "newest"), entity(2L, "older")
        ));

        ConversationHistory history = store.loadRecent(1L, 7L, 2);

        assertThat(history.turns()).extracting(ConversationTurn::question)
            .containsExactly("older", "newest");
        verify(repository).findRecent(1L, 7L, 2);
    }

    @Test
    void shouldMapMysqlInsertFailureWithoutWritingContentToTheError() {
        when(repository.insert(org.mockito.ArgumentMatchers.any(ConversationTurnEntity.class)))
            .thenThrow(new DataIntegrityViolationException("database rejected row"));

        assertThatThrownBy(() -> store.append(1L, 7L, new ConversationTurn("secret question", "secret answer")))
            .isInstanceOfSatisfying(VideoAgentException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
                assertThat(exception.getMessage()).isEqualTo("会话历史保存失败");
            });
    }

    private ConversationTurnEntity entity(long id, String question) {
        ConversationTurnEntity entity = new ConversationTurnEntity();
        entity.setId(id);
        entity.setQuestion(question);
        entity.setAnswer("answer-" + question);
        return entity;
    }
}
