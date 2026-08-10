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

    private final VideoOwnershipService ownershipService;
    private final AnalysisTaskRepository analysisTaskRepository;
    private final AnalysisProperties properties;

    public AnalysisTaskPersistenceService(
        VideoOwnershipService ownershipService,
        AnalysisTaskRepository analysisTaskRepository,
        AnalysisProperties properties
    ) {
        this.ownershipService = ownershipService;
        this.analysisTaskRepository = analysisTaskRepository;
        this.properties = properties;
    }

    @Transactional
    public AnalysisTaskEntity createPending(long videoId, long userId) {
        ownershipService.requireOwned(videoId, userId);

        AnalysisTaskEntity existing = analysisTaskRepository.findByBusinessKey(
            videoId,
            properties.analysisType(),
            properties.modelVersion()
        );
        if (existing != null) {
            throw duplicateTask(existing);
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
        task.setCreatedAt(now);
        task.setUpdatedAt(now);

        try {
            int insertedRows = analysisTaskRepository.insert(task);
            if (insertedRows != 1 || task.getId() == null) {
                throw new IllegalStateException("Analysis task insert did not return an id");
            }
            return task;
        } catch (DuplicateKeyException exception) {
            AnalysisTaskEntity concurrent = analysisTaskRepository.findByBusinessKey(
                videoId,
                properties.analysisType(),
                properties.modelVersion()
            );
            throw duplicateTask(concurrent, exception);
        }
    }

    @Transactional
    public void markDispatchFailed(long taskId, String message) {
        analysisTaskRepository.markFailed(
            taskId,
            "MQ_SEND_FAILED",
            truncate(message),
            LocalDateTime.now()
        );
    }

    private VideoAgentException duplicateTask(AnalysisTaskEntity task) {
        return duplicateTask(task, null);
    }

    private VideoAgentException duplicateTask(AnalysisTaskEntity task, Throwable cause) {
        String suffix = task == null ? "" : "，taskId=" + task.getId() + "，status=" + task.getStatus();
        String message = ErrorCode.ANALYSIS_ALREADY_RUNNING.defaultMessage() + suffix;
        return cause == null
            ? new VideoAgentException(ErrorCode.ANALYSIS_ALREADY_RUNNING, message)
            : new VideoAgentException(ErrorCode.ANALYSIS_ALREADY_RUNNING, message, cause);
    }

    private String truncate(String message) {
        if (message == null || message.isBlank()) {
            return ErrorCode.ANALYSIS_DISPATCH_FAILED.defaultMessage();
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
