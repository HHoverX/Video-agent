package com.videoagent.analysis.service;

import com.videoagent.analysis.dto.AnalysisProgressSnapshot;
import com.videoagent.analysis.dto.StartAnalysisResponse;
import com.videoagent.analysis.entity.AnalysisStage;
import com.videoagent.analysis.entity.AnalysisStatus;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.service.AnalysisTaskPersistenceService.StartAction;
import com.videoagent.analysis.service.AnalysisTaskPersistenceService.StartDecision;
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
     * {@code analysis_task} lifecycle transition + outbox event INSERT atomic.
     * Both prepareStart() and the selected enqueue method run with REQUIRED
     * propagation and join this transaction; if either fails, both roll back.
     */
    @Transactional
    public StartAnalysisResponse start(long videoId, long userId) {
        StartDecision decision = persistenceService.prepareStart(videoId, userId);
        AnalysisTaskEntity task = decision.task();
        if (decision.action() == StartAction.INITIAL_DISPATCH) {
            outboxService.enqueueDispatch(task);
            updateProgress(task, AnalysisStatus.PENDING, AnalysisStage.QUEUED);
        } else if (decision.action() == StartAction.USER_RETRY) {
            outboxService.enqueueRetry(task, task.getProcessingGeneration(), task.getRetryNotBefore());
            updateProgress(task, AnalysisStatus.RETRY_WAITING, AnalysisStage.RETRY_WAITING);
        }

        return new StartAnalysisResponse(task.getId(), videoId, task.getStatus());
    }

    private void updateProgress(AnalysisTaskEntity task, AnalysisStatus status, AnalysisStage stage) {
        progressUpdateService.update(task.getId(), task.getVideoId(), new AnalysisProgressSnapshot(
            status.name(),
            stage.name(),
            0,
            stage.message()
        ));
    }
}
