package com.videoagent.agent.memory;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.util.concurrent.RejectedExecutionException;

class ThreadPoolConversationCompactionSchedulerTest {

    @Test
    void shouldSubmitOnlyIdentifiersAndRunCompaction() {
        ConversationCompactionService service = mock(ConversationCompactionService.class);
        ConversationMemoryMetrics metrics = mock(ConversationMemoryMetrics.class);
        TaskExecutor directExecutor = Runnable::run;
        ThreadPoolConversationCompactionScheduler scheduler =
            new ThreadPoolConversationCompactionScheduler(directExecutor, service, metrics);

        scheduler.schedule(1L, 7L);

        verify(service).compact(1L, 7L);
        verify(metrics).increment("compact.scheduled");
    }

    @Test
    void shouldSwallowRejectedExecution() {
        ConversationCompactionService service = mock(ConversationCompactionService.class);
        ConversationMemoryMetrics metrics = mock(ConversationMemoryMetrics.class);
        TaskExecutor rejectingExecutor = task -> {
            throw new RejectedExecutionException("queue full");
        };
        ThreadPoolConversationCompactionScheduler scheduler =
            new ThreadPoolConversationCompactionScheduler(rejectingExecutor, service, metrics);

        assertThatCode(() -> scheduler.schedule(1L, 7L)).doesNotThrowAnyException();
        verify(metrics).increment("compact.rejected");
    }
}
