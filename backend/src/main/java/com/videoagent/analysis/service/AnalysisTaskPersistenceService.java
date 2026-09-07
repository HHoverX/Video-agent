package com.videoagent.analysis.service;

import com.videoagent.analysis.entity.AnalysisStage;
import com.videoagent.analysis.entity.AnalysisStatus;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.video.service.VideoOwnershipService;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AnalysisTaskPersistenceService {

    public enum StartAction {
        INITIAL_DISPATCH,
        USER_RETRY,
        NONE
    }

    public record StartDecision(AnalysisTaskEntity task, StartAction action) {
    }

    private final VideoOwnershipService ownershipService;
    private final AnalysisTaskRepository analysisTaskRepository;
    private final AnalysisProperties properties;
    private final AnalysisProtectionProperties protectionProperties;

    public AnalysisTaskPersistenceService(
        VideoOwnershipService ownershipService,
        AnalysisTaskRepository analysisTaskRepository,
        AnalysisProperties properties,
        AnalysisProtectionProperties protectionProperties
    ) {
        this.ownershipService = ownershipService;
        this.analysisTaskRepository = analysisTaskRepository;
        this.properties = properties;
        this.protectionProperties = protectionProperties;
    }

    @Transactional
    public StartDecision prepareStart(long videoId, long userId) {
        ownershipService.requireOwned(videoId, userId);

        AnalysisTaskEntity existing = analysisTaskRepository.findByBusinessKey(
            videoId,
            properties.analysisType(),
            properties.modelVersion()
        );
        if (existing != null && !AnalysisStatus.FAILED.name().equals(existing.getStatus())) {
            return prepareExisting(existing);
        }

        if (analysisTaskRepository.countActiveByUserId(userId) >= protectionProperties.maxActivePerUser()) {
            throw new VideoAgentException(
                ErrorCode.ANALYSIS_ACTIVE_LIMIT_EXCEEDED,
                "当前进行中的分析任务过多，请稍后再试"
            );
        }
        if (existing != null) {
            return prepareExisting(existing);
        }

        LocalDateTime now = LocalDateTime.now();
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setVideoId(videoId);
        task.setAnalysisType(properties.analysisType());
        task.setModelVersion(properties.modelVersion());
        task.setStatus(AnalysisStatus.PENDING.name());
        task.setStage(AnalysisStage.QUEUED.name());
        task.setProgress(0);
        task.setRetryCount(0);
        task.setProcessingGeneration(0);
        task.setRetryNotBefore(now);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);

        try {
            int insertedRows = analysisTaskRepository.insert(task);
            if (insertedRows != 1 || task.getId() == null) {
                throw new IllegalStateException("Analysis task insert did not return an id");
            }
            return new StartDecision(task, StartAction.INITIAL_DISPATCH);
        } catch (DuplicateKeyException exception) {
            AnalysisTaskEntity concurrent = analysisTaskRepository.findByBusinessKeyForUpdate(
                videoId,
                properties.analysisType(),
                properties.modelVersion()
            );
            if (concurrent == null) {
                throw exception;
            }
            return new StartDecision(concurrent, StartAction.NONE);
        }
    }

    private StartDecision prepareExisting(AnalysisTaskEntity task) {
        if (!AnalysisStatus.FAILED.name().equals(task.getStatus())) {
            return new StartDecision(task, StartAction.NONE);
        }

        int currentGeneration = task.getProcessingGeneration();
        LocalDateTime now = LocalDateTime.now();
        int updated = analysisTaskRepository.restartFailedForGeneration(
            task.getId(),
            currentGeneration,
            now
        );
        if (updated != 1) {
            AnalysisTaskEntity concurrent = analysisTaskRepository.selectById(task.getId());
            if (concurrent != null && !AnalysisStatus.FAILED.name().equals(concurrent.getStatus())) {
                return new StartDecision(concurrent, StartAction.NONE);
            }
            throw new IllegalStateException("Failed analysis task could not be restarted, taskId=" + task.getId());
        }

        task.setStatus(AnalysisStatus.RETRY_WAITING.name());
        task.setStage(AnalysisStage.RETRY_WAITING.name());
        task.setProgress(0);
        task.setRetryCount(0);
        task.setRetryNotBefore(now);
        task.setProcessingGeneration(currentGeneration + 1);
        task.setProcessingAt(null);
        task.setErrorCode(null);
        task.setErrorMessage(null);
        task.setStartedAt(null);
        task.setFinishedAt(null);
        task.setUpdatedAt(now);
        return new StartDecision(task, StartAction.USER_RETRY);
    }
}
