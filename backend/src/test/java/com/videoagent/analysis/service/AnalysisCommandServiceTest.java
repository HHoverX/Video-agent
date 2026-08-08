package com.videoagent.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.analysis.dto.AnalysisMessage;
import com.videoagent.analysis.dto.AnalysisProgressSnapshot;
import com.videoagent.analysis.dto.StartAnalysisResponse;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.producer.AnalysisTaskProducer;
import com.videoagent.analysis.progress.AnalysisProgressStore;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnalysisCommandServiceTest {

    private final AnalysisTaskPersistenceService persistenceService = mock(AnalysisTaskPersistenceService.class);
    private final AnalysisTaskProducer producer = mock(AnalysisTaskProducer.class);
    private final AnalysisProgressStore progressStore = mock(AnalysisProgressStore.class);
    private AnalysisCommandService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisCommandService(persistenceService, producer, progressStore);
    }

    @Test
    void shouldPersistBeforeDispatchAndReturnPendingTask() {
        AnalysisTaskEntity task = pendingTask();
        when(persistenceService.createPending(7L)).thenReturn(task);

        StartAnalysisResponse response = service.start(7L);

        assertThat(response).isEqualTo(new StartAnalysisResponse(101L, 7L, "PENDING"));
        verify(progressStore).save(101L, new AnalysisProgressSnapshot(
            "PENDING", "QUEUED", 0, "任务已进入队列"
        ));
        verify(producer).send(new AnalysisMessage(101L, 7L));
    }

    @Test
    void shouldMarkTaskFailedWhenBrokerRejectsMessage() {
        AnalysisTaskEntity task = pendingTask();
        when(persistenceService.createPending(7L)).thenReturn(task);
        doThrow(new IllegalStateException("broker unavailable"))
            .when(producer).send(new AnalysisMessage(101L, 7L));

        assertThatThrownBy(() -> service.start(7L))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.ANALYSIS_DISPATCH_FAILED)
            );

        verify(persistenceService).markDispatchFailed(101L, "broker unavailable");
        verify(progressStore).save(101L, new AnalysisProgressSnapshot(
            "FAILED", "FAILED", 0, "分析任务投递失败"
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
