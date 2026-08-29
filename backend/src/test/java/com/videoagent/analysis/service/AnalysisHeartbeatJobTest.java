package com.videoagent.analysis.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.repository.AnalysisTaskRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisHeartbeatJobTest {

    private final AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    private final AnalysisReliabilityProperties properties = new AnalysisReliabilityProperties(
        3, Duration.ofMinutes(15), Duration.ofMinutes(2), Duration.ofHours(2)
    );
    private final ActiveAnalysisLeaseRegistry activeLeases = new ActiveAnalysisLeaseRegistry();
    private AnalysisHeartbeatJob job;

    @BeforeEach
    void setUp() {
        job = new AnalysisHeartbeatJob(taskRepository, properties, activeLeases);
    }

    @Test
    void shouldRefreshProcessingAtOnlyForMatchingGeneration() {
        activeLeases.register(101L, 7L, 3);
        when(taskRepository.heartbeat(eq(101L), eq(3), any(LocalDateTime.class))).thenReturn(1);

        job.heartbeatStaleEligibleTasks();

        verify(taskRepository).heartbeat(eq(101L), eq(3), any(LocalDateTime.class));
    }

    @Test
    void shouldNotRefreshWhenFencingLost() {
        // The recovery moved the task to a new generation; the old heartbeat
        // must not extend the new attempt's lease. heartbeat() returns 0 when
        // the generation no longer matches, and the job must not treat that as
        // a success.
        activeLeases.register(202L, 7L, 2);
        when(taskRepository.heartbeat(eq(202L), eq(2), any(LocalDateTime.class))).thenReturn(0);

        job.heartbeatStaleEligibleTasks();

        verify(taskRepository).heartbeat(eq(202L), eq(2), any(LocalDateTime.class));
    }

    @Test
    void shouldScheduleUsingTheValidatedReliabilityHeartbeatProperty() throws NoSuchMethodException {
        Scheduled scheduled = AnalysisHeartbeatJob.class
            .getMethod("heartbeatStaleEligibleTasks")
            .getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString())
            .isEqualTo("${videoagent.analysis.reliability.heartbeat-interval:2m}");
    }

    @Test
    void shouldRejectHeartbeatIntervalAtOrAboveProcessingLease() {
        assertThatThrownBy(() -> new AnalysisReliabilityProperties(
            3, Duration.ofMinutes(2), Duration.ofMinutes(2), Duration.ofHours(2)
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("heartbeat interval must be smaller");
    }

    private AnalysisTaskEntity processingTask(long id, int generation) {
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setId(id);
        task.setVideoId(7L);
        task.setStatus("PROCESSING");
        task.setProcessingGeneration(generation);
        return task;
    }
}
