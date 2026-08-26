package com.videoagent.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.analysis.dto.AnalysisProgressSnapshot;
import com.videoagent.analysis.dto.StartAnalysisResponse;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.service.AnalysisTaskPersistenceService.StartAction;
import com.videoagent.analysis.service.AnalysisTaskPersistenceService.StartDecision;
import com.videoagent.outbox.OutboxService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnalysisCommandServiceTest {

    private final AnalysisTaskPersistenceService persistenceService = mock(AnalysisTaskPersistenceService.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private final AnalysisProgressUpdateService progressUpdateService = mock(AnalysisProgressUpdateService.class);
    private AnalysisCommandService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisCommandService(persistenceService, outboxService, progressUpdateService);
    }

    @Test
    void shouldPersistTaskAndEnqueueDispatchThenReportPending() {
        AnalysisTaskEntity task = pendingTask();
        when(persistenceService.prepareStart(7L, 5L))
            .thenReturn(new StartDecision(task, StartAction.INITIAL_DISPATCH));
        when(outboxService.enqueueDispatch(task)).thenReturn(1L);

        StartAnalysisResponse response = service.start(7L, 5L);

        assertThat(response).isEqualTo(new StartAnalysisResponse(101L, 7L, "PENDING"));
        verify(persistenceService).prepareStart(7L, 5L);
        verify(outboxService).enqueueDispatch(task);
        verify(progressUpdateService).update(101L, 7L, new AnalysisProgressSnapshot(
            "PENDING", "QUEUED", 0, "任务已进入队列"
        ));
    }

    @Test
    void shouldEnqueueGenerationScopedRetryForFailedTask() {
        AnalysisTaskEntity task = pendingTask();
        task.setStatus("RETRY_WAITING");
        task.setProcessingGeneration(5);
        task.setRetryNotBefore(java.time.LocalDateTime.now());
        when(persistenceService.prepareStart(7L, 5L))
            .thenReturn(new StartDecision(task, StartAction.USER_RETRY));

        StartAnalysisResponse response = service.start(7L, 5L);

        assertThat(response).isEqualTo(new StartAnalysisResponse(101L, 7L, "RETRY_WAITING"));
        verify(outboxService).enqueueRetry(task, 5, task.getRetryNotBefore());
        verify(outboxService, never()).enqueueDispatch(task);
        verify(progressUpdateService).update(101L, 7L, new AnalysisProgressSnapshot(
            "RETRY_WAITING", "RETRY_WAITING", 0, "分析暂时失败，正在重试"
        ));
    }

    @Test
    void shouldReturnExistingActiveTaskWithoutEnqueueingAnotherEvent() {
        AnalysisTaskEntity task = pendingTask();
        task.setStatus("PROCESSING");
        when(persistenceService.prepareStart(7L, 5L))
            .thenReturn(new StartDecision(task, StartAction.NONE));

        StartAnalysisResponse response = service.start(7L, 5L);

        assertThat(response).isEqualTo(new StartAnalysisResponse(101L, 7L, "PROCESSING"));
        verify(outboxService, never()).enqueueDispatch(task);
        verify(outboxService, never()).enqueueRetry(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
        verify(progressUpdateService, never()).update(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    private AnalysisTaskEntity pendingTask() {
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setId(101L);
        task.setVideoId(7L);
        task.setStatus("PENDING");
        return task;
    }
}
