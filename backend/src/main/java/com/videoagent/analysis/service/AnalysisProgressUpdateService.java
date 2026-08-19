package com.videoagent.analysis.service;

import com.videoagent.analysis.dto.AnalysisProgressEventResponse;
import com.videoagent.analysis.dto.AnalysisProgressSnapshot;
import com.videoagent.analysis.event.AnalysisEventBroadcaster;
import com.videoagent.analysis.progress.AnalysisProgressStore;

import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AnalysisProgressUpdateService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisProgressUpdateService.class);

    private final AnalysisProgressStore progressStore;
    private final AnalysisEventBroadcaster broadcaster;

    public AnalysisProgressUpdateService(
        AnalysisProgressStore progressStore,
        AnalysisEventBroadcaster broadcaster
    ) {
        this.progressStore = progressStore;
        this.broadcaster = broadcaster;
    }

    public void update(long taskId, long videoId, AnalysisProgressSnapshot snapshot) {
        update(taskId, videoId, snapshot, null, null);
    }

    public void update(
        long taskId,
        long videoId,
        AnalysisProgressSnapshot snapshot,
        String errorCode,
        String errorMessage
    ) {
        try {
            progressStore.save(taskId, snapshot);
        } catch (RuntimeException exception) {
            // Redis is an acceleration layer. Durable lifecycle truth remains
            // in analysis_task; a cache outage must not roll back task/outbox.
            log.warn("[taskId={}][videoId={}] progress cache update failed", taskId, videoId, exception);
        }
        try {
            broadcaster.publish(new AnalysisProgressEventResponse(
                taskId,
                videoId,
                snapshot.status(),
                snapshot.stage(),
                snapshot.progress(),
                snapshot.message(),
                errorCode,
                errorMessage
            ));
        } catch (RuntimeException exception) {
            log.warn("[taskId={}][videoId={}] transient SSE publish failed", taskId, videoId, exception);
        }
    }
}
