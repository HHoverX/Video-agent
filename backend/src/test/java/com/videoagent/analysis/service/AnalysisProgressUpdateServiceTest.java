package com.videoagent.analysis.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.videoagent.analysis.dto.AnalysisProgressEventResponse;
import com.videoagent.analysis.dto.AnalysisProgressSnapshot;
import com.videoagent.analysis.event.AnalysisEventBroadcaster;
import com.videoagent.analysis.progress.AnalysisProgressStore;

import org.junit.jupiter.api.Test;

class AnalysisProgressUpdateServiceTest {

    @Test
    void shouldPersistRedisSnapshotAndBroadcastSameProgress() {
        AnalysisProgressStore progressStore = mock(AnalysisProgressStore.class);
        AnalysisEventBroadcaster broadcaster = mock(AnalysisEventBroadcaster.class);
        AnalysisProgressUpdateService service = new AnalysisProgressUpdateService(progressStore, broadcaster);
        AnalysisProgressSnapshot snapshot = new AnalysisProgressSnapshot(
            "PROCESSING", "SUMMARIZING", 85, "正在生成 AI 摘要"
        );

        service.update(101L, 7L, snapshot);

        verify(progressStore).save(101L, snapshot);
        verify(broadcaster).publish(new AnalysisProgressEventResponse(
            101L, 7L, "PROCESSING", "SUMMARIZING", 85, "正在生成 AI 摘要", null, null
        ));
    }
}
