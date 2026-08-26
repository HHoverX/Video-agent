package com.videoagent.analysis.service;

import com.videoagent.analysis.dto.AnalysisProgressSnapshot;
import com.videoagent.analysis.dto.AnalysisTaskResponse;
import com.videoagent.analysis.entity.AnalysisStage;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.progress.AnalysisProgressStore;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.video.service.VideoOwnershipService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisQueryService {

    private final AnalysisTaskRepository analysisTaskRepository;
    private final AnalysisProgressStore progressStore;
    private final VideoOwnershipService ownershipService;

    public AnalysisQueryService(
        AnalysisTaskRepository analysisTaskRepository,
        AnalysisProgressStore progressStore,
        VideoOwnershipService ownershipService
    ) {
        this.analysisTaskRepository = analysisTaskRepository;
        this.progressStore = progressStore;
        this.ownershipService = ownershipService;
    }

    @Transactional(readOnly = true)
    public AnalysisTaskResponse getTask(long taskId, long userId) {
        AnalysisTaskEntity task = analysisTaskRepository.selectById(taskId);
        if (task == null || !ownershipService.isOwned(task.getVideoId(), userId)) {
            throw new VideoAgentException(ErrorCode.ANALYSIS_NOT_FOUND);
        }

        AnalysisProgressSnapshot persisted = mysqlSnapshot(task);
        AnalysisProgressSnapshot progress = progressStore.find(taskId)
            .filter(snapshot -> task.getStatus().equals(snapshot.status()))
            .orElse(persisted);

        return new AnalysisTaskResponse(
            task.getId(),
            task.getVideoId(),
            progress.status(),
            progress.stage(),
            progress.progress(),
            progress.message(),
            task.getErrorCode(),
            task.getErrorMessage(),
            task.getCreatedAt(),
            task.getStartedAt(),
            task.getFinishedAt()
        );
    }

    private AnalysisProgressSnapshot mysqlSnapshot(AnalysisTaskEntity task) {
        return new AnalysisProgressSnapshot(
            task.getStatus(),
            task.getStage(),
            task.getProgress(),
            mysqlFallbackMessage(task)
        );
    }

    private String mysqlFallbackMessage(AnalysisTaskEntity task) {
        if (task.getErrorMessage() != null && !task.getErrorMessage().isBlank()) {
            return task.getErrorMessage();
        }
        return AnalysisStage.messageFor(task.getStage());
    }
}
