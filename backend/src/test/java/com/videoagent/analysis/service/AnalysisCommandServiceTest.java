package com.videoagent.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.analysis.dto.AnalysisProgressSnapshot;
import com.videoagent.analysis.dto.StartAnalysisResponse;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
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
        when(persistenceService.createPending(7L, 5L)).thenReturn(task);
        when(outboxService.enqueueDispatch(task)).thenReturn(1L);

        StartAnalysisResponse response = service.start(7L, 5L);

        assertThat(response).isEqualTo(new StartAnalysisResponse(101L, 7L, "PENDING"));
        verify(persistenceService).createPending(7L, 5L);
        verify(outboxService).enqueueDispatch(task);
        verify(progressUpdateService).update(101L, 7L, new AnalysisProgressSnapshot(
            "PENDING", "QUEUED", 0, "任务已进入队列"
        ));
    }

    private AnalysisTaskEntity pendingTask() {
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setId(101L);
        task.setVideoId(7L);
        task.setStatus("PENDING");
        return task;
    }
}
