package com.videoagent.analysis.service;

import com.videoagent.analysis.dto.AnalysisProgressEventResponse;
import com.videoagent.analysis.dto.AnalysisProgressSnapshot;
import com.videoagent.analysis.event.AnalysisEventBroadcaster;
import com.videoagent.analysis.progress.AnalysisProgressStore;

import org.springframework.stereotype.Service;

@Service
public class AnalysisProgressUpdateService {

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
        progressStore.save(taskId, snapshot);
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
    }
}
