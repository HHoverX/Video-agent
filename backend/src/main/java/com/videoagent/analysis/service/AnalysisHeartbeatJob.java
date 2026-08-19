package com.videoagent.analysis.service;

import com.videoagent.analysis.repository.AnalysisTaskRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

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
    private final ActiveAnalysisLeaseRegistry activeLeases;

    public AnalysisHeartbeatJob(
        AnalysisTaskRepository taskRepository,
        AnalysisReliabilityProperties properties,
        ActiveAnalysisLeaseRegistry activeLeases
    ) {
        this.taskRepository = taskRepository;
        this.properties = properties;
        this.activeLeases = activeLeases;
    }

    @Scheduled(fixedDelayString = "${videoagent.analysis.heartbeat-interval-ms:120000}")
    public void heartbeatStaleEligibleTasks() {
        LocalDateTime now = LocalDateTime.now();
        Instant clock = Instant.now();
        for (ActiveAnalysisLeaseRegistry.ActiveLease lease : activeLeases.snapshot()) {
            if (Duration.between(lease.startedAt(), clock).compareTo(properties.maxExecutionTime()) >= 0) {
                activeLeases.unregister(lease.taskId(), lease.generation());
                log.warn(
                    "[taskId={}][videoId={}][generation={}] max execution time reached; heartbeat stopped so recovery can fence this worker",
                    lease.taskId(), lease.videoId(), lease.generation()
                );
                continue;
            }
            int updated = taskRepository.heartbeat(
                lease.taskId(),
                lease.generation(),
                now
            );
            if (updated != 1) {
                activeLeases.unregister(lease.taskId(), lease.generation());
                log.info(
                    "[taskId={}][videoId={}][generation={}] heartbeat lost fencing; task moved on",
                    lease.taskId(), lease.videoId(), lease.generation()
                );
            }
        }
    }
}
