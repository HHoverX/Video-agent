package com.videoagent.analysis.service;

import com.videoagent.analysis.dto.AnalysisProgressSnapshot;
import com.videoagent.analysis.dto.StartAnalysisResponse;
import com.videoagent.analysis.entity.AnalysisStage;
import com.videoagent.analysis.entity.AnalysisStatus;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.outbox.OutboxService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisCommandService {

    private final AnalysisTaskPersistenceService persistenceService;
    private final OutboxService outboxService;
    private final AnalysisProgressUpdateService progressUpdateService;

    public AnalysisCommandService(
        AnalysisTaskPersistenceService persistenceService,
        OutboxService outboxService,
        AnalysisProgressUpdateService progressUpdateService
    ) {
        this.persistenceService = persistenceService;
        this.outboxService = outboxService;
        this.progressUpdateService = progressUpdateService;
    }

    /**
     * CRITICAL #1: this is the single Spring transaction boundary that makes
     * {@code analysis_task INSERT} + {@code initial outbox event INSERT} atomic.
     * Both createPending() and enqueueDispatch() run with REQUIRED propagation
     * and join this transaction; if either fails, both roll back.
     */
    @Transactional
    public StartAnalysisResponse start(long videoId, long userId) {
        AnalysisTaskEntity task = persistenceService.createPending(videoId, userId);
        outboxService.enqueueDispatch(task);
        progressUpdateService.update(task.getId(), videoId, new AnalysisProgressSnapshot(
            AnalysisStatus.PENDING.name(),
            AnalysisStage.QUEUED.name(),
            0,
            AnalysisStage.QUEUED.message()
        ));

        return new StartAnalysisResponse(task.getId(), videoId, AnalysisStatus.PENDING.name());
    }
}
