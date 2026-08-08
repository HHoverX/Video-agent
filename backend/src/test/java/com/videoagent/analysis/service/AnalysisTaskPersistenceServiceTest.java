package com.videoagent.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

class AnalysisTaskPersistenceServiceTest {

    private final VideoRepository videoRepository = mock(VideoRepository.class);
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
        service = new AnalysisTaskPersistenceService(videoRepository, taskRepository, properties);
    }

    @Test
    void shouldCreatePendingTaskForExistingVideo() {
        when(videoRepository.selectById(7L)).thenReturn(new VideoEntity());
        when(taskRepository.findByBusinessKey(7L, "FRAMEWORK", "m3-simulation-v1")).thenReturn(null);
        when(taskRepository.insert(any(AnalysisTaskEntity.class))).thenAnswer(invocation -> {
            AnalysisTaskEntity task = invocation.getArgument(0);
            task.setId(101L);
            return 1;
        });

        AnalysisTaskEntity task = service.createPending(7L);

        assertThat(task.getId()).isEqualTo(101L);
        assertThat(task.getStatus()).isEqualTo("PENDING");
        assertThat(task.getStage()).isEqualTo("QUEUED");
        assertThat(task.getProgress()).isZero();
        assertThat(task.getRetryCount()).isZero();
    }

    @Test
    void shouldRejectUnknownVideo() {
        when(videoRepository.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.createPending(999L))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.VIDEO_NOT_FOUND)
            );
    }

    @Test
    void shouldRejectExistingBusinessTask() {
        when(videoRepository.selectById(7L)).thenReturn(new VideoEntity());
        AnalysisTaskEntity existing = new AnalysisTaskEntity();
        existing.setId(88L);
        existing.setStatus("PROCESSING");
        when(taskRepository.findByBusinessKey(7L, "FRAMEWORK", "m3-simulation-v1")).thenReturn(existing);

        assertThatThrownBy(() -> service.createPending(7L))
            .isInstanceOfSatisfying(VideoAgentException.class, exception -> {
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.ANALYSIS_ALREADY_RUNNING);
                assertThat(exception.getMessage()).contains("taskId=88", "status=PROCESSING");
            });
    }
}
