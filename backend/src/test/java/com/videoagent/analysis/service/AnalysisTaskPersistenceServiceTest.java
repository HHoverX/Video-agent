package com.videoagent.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.analysis.entity.AnalysisStatus;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.analysis.service.AnalysisTaskPersistenceService.StartAction;
import com.videoagent.analysis.service.AnalysisTaskPersistenceService.StartDecision;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.service.VideoOwnershipService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

import java.time.Duration;
import java.time.LocalDateTime;

class AnalysisTaskPersistenceServiceTest {

    private final VideoOwnershipService ownershipService = mock(VideoOwnershipService.class);
    private final AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    private AnalysisTaskPersistenceService service;

    @BeforeEach
    void setUp() {
        AnalysisProperties properties = new AnalysisProperties(
            "VIDEO_ANALYZE_TOPIC",
            "test-consumer",
            "FRAMEWORK",
            "m3-simulation-v1",
            Duration.ofHours(24)
        );
        service = new AnalysisTaskPersistenceService(ownershipService, taskRepository, properties);
    }

    @Test
    void shouldCreatePendingTaskForExistingVideo() {
        when(ownershipService.requireOwned(7L, 5L)).thenReturn(new VideoEntity());
        when(taskRepository.findByBusinessKeyForUpdate(7L, "FRAMEWORK", "m3-simulation-v1"))
            .thenReturn(null);
        when(taskRepository.insert(any(AnalysisTaskEntity.class))).thenAnswer(invocation -> {
            AnalysisTaskEntity task = invocation.getArgument(0);
            task.setId(101L);
            return 1;
        });

        StartDecision decision = service.prepareStart(7L, 5L);
        AnalysisTaskEntity task = decision.task();

        assertThat(decision.action()).isEqualTo(StartAction.INITIAL_DISPATCH);
        assertThat(task.getId()).isEqualTo(101L);
        assertThat(task.getStatus()).isEqualTo("PENDING");
        assertThat(task.getStage()).isEqualTo("QUEUED");
        assertThat(task.getProgress()).isZero();
        assertThat(task.getRetryCount()).isZero();
    }

    @Test
    void shouldRejectUnknownVideo() {
        when(ownershipService.requireOwned(999L, 5L))
            .thenThrow(new VideoAgentException(ErrorCode.VIDEO_NOT_FOUND));

        assertThatThrownBy(() -> service.prepareStart(999L, 5L))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.VIDEO_NOT_FOUND)
            );
    }

    @ParameterizedTest
    @EnumSource(value = AnalysisStatus.class, names = {"PENDING", "PROCESSING", "RETRY_WAITING", "SUCCESS"})
    void shouldReturnExistingTaskWithoutChangingLifecycle(AnalysisStatus status) {
        when(ownershipService.requireOwned(7L, 5L)).thenReturn(new VideoEntity());
        AnalysisTaskEntity existing = new AnalysisTaskEntity();
        existing.setId(88L);
        existing.setStatus(status.name());
        when(taskRepository.findByBusinessKeyForUpdate(7L, "FRAMEWORK", "m3-simulation-v1"))
            .thenReturn(existing);

        StartDecision decision = service.prepareStart(7L, 5L);

        assertThat(decision.task()).isSameAs(existing);
        assertThat(decision.action()).isEqualTo(StartAction.NONE);
        verify(taskRepository, never()).restartFailedForGeneration(anyLong(), anyInt(), any());
        verify(taskRepository, never()).insert(any(AnalysisTaskEntity.class));
    }

    @Test
    void shouldRestartFailedTaskInPlaceAndPreserveFailureHistory() {
        when(ownershipService.requireOwned(7L, 5L)).thenReturn(new VideoEntity());
        LocalDateTime previousStartedAt = LocalDateTime.now().minusMinutes(5);
        AnalysisTaskEntity failed = existingTask(AnalysisStatus.FAILED, 4);
        failed.setRetryCount(3);
        failed.setErrorCode("CURRENT_ERROR");
        failed.setErrorMessage("current failure");
        failed.setLastErrorCode("HISTORICAL_ERROR");
        failed.setLastErrorMessage("historical failure");
        failed.setLastFailureStage("SUMMARIZING");
        failed.setStartedAt(previousStartedAt);
        failed.setFinishedAt(LocalDateTime.now().minusMinutes(1));
        when(taskRepository.findByBusinessKeyForUpdate(7L, "FRAMEWORK", "m3-simulation-v1"))
            .thenReturn(failed);
        when(taskRepository.restartFailedForGeneration(anyLong(), anyInt(), any()))
            .thenReturn(1);

        StartDecision decision = service.prepareStart(7L, 5L);

        assertThat(decision.action()).isEqualTo(StartAction.USER_RETRY);
        assertThat(decision.task()).isSameAs(failed);
        assertThat(failed.getId()).isEqualTo(88L);
        assertThat(failed.getStatus()).isEqualTo("RETRY_WAITING");
        assertThat(failed.getStage()).isEqualTo("RETRY_WAITING");
        assertThat(failed.getRetryCount()).isZero();
        assertThat(failed.getProcessingGeneration()).isEqualTo(5);
        assertThat(failed.getRetryNotBefore()).isNotNull();
        assertThat(failed.getStartedAt()).isNull();
        assertThat(failed.getFinishedAt()).isNull();
        assertThat(failed.getErrorCode()).isNull();
        assertThat(failed.getErrorMessage()).isNull();
        assertThat(failed.getLastErrorCode()).isEqualTo("HISTORICAL_ERROR");
        assertThat(failed.getLastErrorMessage()).isEqualTo("historical failure");
        assertThat(failed.getLastFailureStage()).isEqualTo("SUMMARIZING");
        verify(taskRepository).restartFailedForGeneration(88L, 4, failed.getRetryNotBefore());
    }

    @Test
    void shouldCheckOwnershipBeforeLockingTask() {
        when(ownershipService.requireOwned(7L, 5L)).thenReturn(new VideoEntity());
        AnalysisTaskEntity existing = existingTask(AnalysisStatus.PROCESSING, 2);
        when(taskRepository.findByBusinessKeyForUpdate(7L, "FRAMEWORK", "m3-simulation-v1"))
            .thenReturn(existing);

        service.prepareStart(7L, 5L);

        InOrder order = inOrder(ownershipService, taskRepository);
        order.verify(ownershipService).requireOwned(7L, 5L);
        order.verify(taskRepository).findByBusinessKeyForUpdate(7L, "FRAMEWORK", "m3-simulation-v1");
    }

    @Test
    void shouldOnlyRestartFailedTaskOnceAcrossRepeatedStarts() {
        when(ownershipService.requireOwned(7L, 5L)).thenReturn(new VideoEntity());
        AnalysisTaskEntity failed = existingTask(AnalysisStatus.FAILED, 4);
        when(taskRepository.findByBusinessKeyForUpdate(7L, "FRAMEWORK", "m3-simulation-v1"))
            .thenReturn(failed);
        when(taskRepository.restartFailedForGeneration(anyLong(), anyInt(), any()))
            .thenReturn(1);

        StartDecision first = service.prepareStart(7L, 5L);
        StartDecision second = service.prepareStart(7L, 5L);

        assertThat(first.action()).isEqualTo(StartAction.USER_RETRY);
        assertThat(second.action()).isEqualTo(StartAction.NONE);
        assertThat(second.task().getId()).isEqualTo(first.task().getId());
        assertThat(second.task().getProcessingGeneration()).isEqualTo(5);
        verify(taskRepository).restartFailedForGeneration(anyLong(), anyInt(), any());
    }

    private AnalysisTaskEntity existingTask(AnalysisStatus status, int generation) {
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setId(88L);
        task.setVideoId(7L);
        task.setStatus(status.name());
        task.setStage(status == AnalysisStatus.FAILED ? "FAILED" : "PROCESSING");
        task.setProgress(80);
        task.setProcessingGeneration(generation);
        return task;
    }
}
