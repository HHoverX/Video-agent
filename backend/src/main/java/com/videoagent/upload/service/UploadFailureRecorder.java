package com.videoagent.upload.service;

import com.videoagent.upload.entity.UploadSessionStatus;
import com.videoagent.upload.entity.VideoUploadSessionEntity;
import com.videoagent.upload.repository.VideoUploadSessionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class UploadFailureRecorder {

    private final VideoUploadSessionRepository sessionRepository;

    public UploadFailureRecorder(VideoUploadSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRetryableCompletionFailure(long userId, String uploadId, String message) {
        VideoUploadSessionEntity session = sessionRepository.lockById(uploadId);
        if (session == null || session.getUserId() == null || session.getUserId() != userId) {
            return;
        }
        if (UploadSessionStatus.COMPLETED.name().equals(session.getStatus())
            || UploadSessionStatus.CANCELLED.name().equals(session.getStatus())
            || UploadSessionStatus.EXPIRED.name().equals(session.getStatus())) {
            return;
        }
        session.setStatus(UploadSessionStatus.FAILED.name());
        session.setLastError(safeMessage(message));
        session.setUpdatedAt(LocalDateTime.now());
        sessionRepository.updateById(session);
    }

    private String safeMessage(String message) {
        String value = message == null || message.isBlank() ? "视频分片合并失败" : message;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
