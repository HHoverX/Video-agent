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

import java.util.Optional;

@Service
public class AnalysisQueryService {

    private final AnalysisTaskRepository analysisTaskRepository;
    private final AnalysisProgressStore progressStore;
    private final VideoOwnershipService ownershipService;
    private final AnalysisProperties properties;

    public AnalysisQueryService(
        AnalysisTaskRepository analysisTaskRepository,
        AnalysisProgressStore progressStore,
        VideoOwnershipService ownershipService,
        AnalysisProperties properties
    ) {
        this.analysisTaskRepository = analysisTaskRepository;
        this.progressStore = progressStore;
        this.ownershipService = ownershipService;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public AnalysisTaskResponse getTask(long taskId, long userId) {
        AnalysisTaskEntity task = analysisTaskRepository.selectById(taskId);
        if (task == null || !ownershipService.isOwned(task.getVideoId(), userId)) {
            throw new VideoAgentException(ErrorCode.ANALYSIS_NOT_FOUND);
        }

        return toResponse(task);
    }

    @Transactional(readOnly = true)
    public Optional<AnalysisTaskResponse> getCurrentTask(long videoId, long userId) {
        ownershipService.requireOwned(videoId, userId);
        return Optional.ofNullable(analysisTaskRepository.findByBusinessKey(
            videoId,
            properties.analysisType(),
            properties.modelVersion()
        )).map(this::toResponse);
    }

    private AnalysisTaskResponse toResponse(AnalysisTaskEntity task) {
        AnalysisProgressSnapshot persisted = mysqlSnapshot(task);
        AnalysisProgressSnapshot progress = progressStore.find(task.getId())
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
