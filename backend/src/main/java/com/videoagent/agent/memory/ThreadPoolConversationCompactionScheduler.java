package com.videoagent.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.RejectedExecutionException;

@Component
public class ThreadPoolConversationCompactionScheduler implements ConversationCompactionScheduler {

    private static final Logger log = LoggerFactory.getLogger(
        ThreadPoolConversationCompactionScheduler.class);

    private final TaskExecutor taskExecutor;
    private final ConversationCompactionService compactionService;
    private final ConversationMemoryMetrics metrics;

    public ThreadPoolConversationCompactionScheduler(
        @Qualifier("conversationMemoryCompactionExecutor") TaskExecutor taskExecutor,
        ConversationCompactionService compactionService,
        ConversationMemoryMetrics metrics
    ) {
        this.taskExecutor = taskExecutor;
        this.compactionService = compactionService;
        this.metrics = metrics;
    }

    @Override
    public void schedule(long userId, long videoId) {
        try {
            taskExecutor.execute(() -> compactionService.compact(userId, videoId));
            metrics.increment("compact.scheduled");
        } catch (RejectedExecutionException exception) {
            metrics.increment("compact.rejected");
            log.warn("event=memory.compact.rejected userId={} videoId={} exceptionClass={}",
                userId, videoId, exception.getClass().getSimpleName());
        }
    }
}
