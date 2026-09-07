package com.videoagent.upload.service;

import com.videoagent.upload.entity.VideoUploadSessionEntity;
import com.videoagent.upload.repository.VideoUploadSessionRepository;
import com.videoagent.video.service.VideoUploadProperties;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class UploadCompletionRecoveryJob {

    private static final int RECOVERY_BATCH = 100;

    private final VideoUploadSessionRepository sessionRepository;
    private final UploadFailureRecorder failureRecorder;
    private final VideoUploadProperties properties;

    public UploadCompletionRecoveryJob(
        VideoUploadSessionRepository sessionRepository,
        UploadFailureRecorder failureRecorder,
        VideoUploadProperties properties
    ) {
        this.sessionRepository = sessionRepository;
        this.failureRecorder = failureRecorder;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${videoagent.upload.completion-recovery-interval-ms:30000}")
    public void recoverTimedOutCompletions() {
        LocalDateTime cutoff = LocalDateTime.now().minus(properties.completionTimeout());
        for (VideoUploadSessionEntity session : sessionRepository.findTimedOutCompletions(cutoff, RECOVERY_BATCH)) {
            failureRecorder.recordTimedOutCompletion(
                session.getId(), session.getCompletionToken(), cutoff
            );
        }
    }
}
