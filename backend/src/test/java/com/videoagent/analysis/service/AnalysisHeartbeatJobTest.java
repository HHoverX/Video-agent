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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

class AnalysisHeartbeatJobTest {

    private final AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    private final AnalysisReliabilityProperties properties = new AnalysisReliabilityProperties(3, Duration.ofMinutes(15), Duration.ofMinutes(2));
    private AnalysisHeartbeatJob job;

    @BeforeEach
    void setUp() {
        job = new AnalysisHeartbeatJob(taskRepository, properties);
    }

    @Test
    void shouldRefreshProcessingAtOnlyForMatchingGeneration() {
        AnalysisTaskEntity processing = processingTask(101L, 3);
        when(taskRepository.findProcessingTasks()).thenReturn(List.of(processing));
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
        AnalysisTaskEntity processing = processingTask(202L, 2);
        when(taskRepository.findProcessingTasks()).thenReturn(List.of(processing));
        when(taskRepository.heartbeat(eq(202L), eq(2), any(LocalDateTime.class))).thenReturn(0);

        job.heartbeatStaleEligibleTasks();

        verify(taskRepository).heartbeat(eq(202L), eq(2), any(LocalDateTime.class));
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
