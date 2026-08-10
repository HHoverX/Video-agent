package com.videoagent.analysis.service;

import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.repository.AnalysisTaskRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Lightweight, generation-guarded heartbeat. Runs on a modest interval (much
 * smaller than the processing lease) and refreshes processing_at only for
 * PROCESSING tasks whose generation still matches the worker that claimed them.
 * An abandoned worker can never extend a new attempt's lease, because the
 * heartbeat carries the expected generation as a fencing token.
 */
@Component
public class AnalysisHeartbeatJob {

    private static final Logger log = LoggerFactory.getLogger(AnalysisHeartbeatJob.class);

    private final AnalysisTaskRepository taskRepository;
    private final AnalysisReliabilityProperties properties;

    public AnalysisHeartbeatJob(
        AnalysisTaskRepository taskRepository,
        AnalysisReliabilityProperties properties
    ) {
        this.taskRepository = taskRepository;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${videoagent.analysis.heartbeat-interval-ms:120000}")
    public void heartbeatStaleEligibleTasks() {
        List<AnalysisTaskEntity> processing = taskRepository.findProcessingTasks();
        LocalDateTime now = LocalDateTime.now();
        for (AnalysisTaskEntity task : processing) {
            int generation = task.getProcessingGeneration() == null ? 0 : task.getProcessingGeneration();
            int updated = taskRepository.heartbeat(
                task.getId(),
                generation,
                now
            );
            if (updated != 1) {
                // Fencing lost: the recovery (or another worker) moved the task
                // forward; this heartbeat must not refresh a different attempt.
                log.info(
                    "[taskId={}][videoId={}][generation={}] heartbeat lost fencing; task moved on",
                    task.getId(), task.getVideoId(), generation
                );
            }
        }
    }
}
