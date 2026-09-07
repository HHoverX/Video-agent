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
    public void recordRetryableCompletionFailure(
        long userId,
        String uploadId,
        String completionToken,
        String message
    ) {
        VideoUploadSessionEntity session = sessionRepository.lockById(uploadId);
        if (session == null || session.getUserId() == null || session.getUserId() != userId) {
            return;
        }
        if (!UploadSessionStatus.COMPLETING.name().equals(session.getStatus())
            || completionToken == null
            || !completionToken.equals(session.getCompletionToken())) {
            return;
        }
        sessionRepository.markCompletionFailed(
            uploadId, completionToken, safeMessage(message), LocalDateTime.now()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordTimedOutCompletion(String uploadId, String completionToken, LocalDateTime cutoff) {
        VideoUploadSessionEntity session = sessionRepository.lockById(uploadId);
        if (session == null
            || !UploadSessionStatus.COMPLETING.name().equals(session.getStatus())
            || completionToken == null
            || !completionToken.equals(session.getCompletionToken())
            || session.getCompletingAt() == null
            || !session.getCompletingAt().isBefore(cutoff)) {
            return;
        }
        sessionRepository.markCompletionFailed(
            uploadId, completionToken, "COMPLETING_TIMEOUT", LocalDateTime.now()
        );
    }

    private String safeMessage(String message) {
        String value = message == null || message.isBlank() ? "视频分片合并失败" : message;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
