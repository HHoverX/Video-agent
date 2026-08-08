package com.videoagent.analysis.service;

import com.videoagent.analysis.dto.AnalysisMessage;
import com.videoagent.analysis.dto.AnalysisProgressSnapshot;
import com.videoagent.analysis.dto.StartAnalysisResponse;
import com.videoagent.analysis.entity.AnalysisStage;
import com.videoagent.analysis.entity.AnalysisStatus;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.producer.AnalysisTaskProducer;
import com.videoagent.analysis.progress.AnalysisProgressStore;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import org.springframework.stereotype.Service;

@Service
public class AnalysisCommandService {

    private final AnalysisTaskPersistenceService persistenceService;
    private final AnalysisTaskProducer producer;
    private final AnalysisProgressStore progressStore;

    public AnalysisCommandService(
        AnalysisTaskPersistenceService persistenceService,
        AnalysisTaskProducer producer,
        AnalysisProgressStore progressStore
    ) {
        this.persistenceService = persistenceService;
        this.producer = producer;
        this.progressStore = progressStore;
    }

    public StartAnalysisResponse start(long videoId) {
        AnalysisTaskEntity task = persistenceService.createPending(videoId);
        progressStore.save(task.getId(), new AnalysisProgressSnapshot(
            AnalysisStatus.PENDING.name(),
            AnalysisStage.QUEUED.name(),
            0,
            AnalysisStage.QUEUED.message()
        ));

        try {
            producer.send(new AnalysisMessage(task.getId(), videoId));
        } catch (RuntimeException exception) {
            persistenceService.markDispatchFailed(task.getId(), exception.getMessage());
            progressStore.save(task.getId(), new AnalysisProgressSnapshot(
                AnalysisStatus.FAILED.name(),
                AnalysisStage.FAILED.name(),
                0,
                ErrorCode.ANALYSIS_DISPATCH_FAILED.defaultMessage()
            ));
            throw new VideoAgentException(
                ErrorCode.ANALYSIS_DISPATCH_FAILED,
                ErrorCode.ANALYSIS_DISPATCH_FAILED.defaultMessage(),
                exception
            );
        }

        return new StartAnalysisResponse(task.getId(), videoId, AnalysisStatus.PENDING.name());
    }
}
