package com.videoagent.upload.service;

import com.videoagent.storage.ObjectStorageService;
import com.videoagent.upload.entity.VideoUploadSessionEntity;
import com.videoagent.upload.repository.VideoUploadSessionRepository;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

@Component
public class UploadTemporaryObjectCleaner {

    private static final Logger log = LoggerFactory.getLogger(UploadTemporaryObjectCleaner.class);
    private static final int CLEANUP_BATCH = 100;

    private final VideoUploadSessionRepository sessionRepository;
    private final ObjectStorageService storageService;
    private final VideoRepository videoRepository;

    public UploadTemporaryObjectCleaner(
        VideoUploadSessionRepository sessionRepository,
        ObjectStorageService storageService,
        VideoRepository videoRepository
    ) {
        this.sessionRepository = sessionRepository;
        this.storageService = storageService;
        this.videoRepository = videoRepository;
    }

    @Scheduled(fixedDelayString = "${videoagent.upload.cleanup-interval-ms:300000}")
    public void cleanupExpiredAndTemporaryObjects() {
        LocalDateTime now = LocalDateTime.now();
        for (VideoUploadSessionEntity session : sessionRepository.findExpired(now, CLEANUP_BATCH)) {
            sessionRepository.markExpired(session.getId(), now);
        }
        for (VideoUploadSessionEntity session : sessionRepository.findCleanupPending(CLEANUP_BATCH)) {
            cleanupNow(session);
        }
    }

    public void cleanupAfterCommit(VideoUploadSessionEntity session) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanupNow(session);
                }
            });
        } else {
            cleanupNow(session);
        }
    }

    void cleanupNow(VideoUploadSessionEntity session) {
        try {
            for (int partNumber = 1; partNumber <= session.getTotalParts(); partNumber++) {
                storageService.removeObject(UploadKeyPolicy.partObjectKey(session.getTempPrefix(), partNumber));
            }
            if (shouldRemoveFinalObject(session)) {
                storageService.removeObject(session.getObjectKey());
            }
            sessionRepository.markTemporaryObjectsCleaned(session.getId(), LocalDateTime.now());
        } catch (RuntimeException exception) {
            log.warn("[uploadId={}][stage=CLEANUP] temporary object cleanup will be retried", session.getId(), exception);
        }
    }

    private boolean shouldRemoveFinalObject(VideoUploadSessionEntity session) {
        if (session.getVideoId() == null) {
            return true;
        }
        VideoEntity canonicalVideo = videoRepository.selectById(session.getVideoId());
        return canonicalVideo == null || !session.getObjectKey().equals(canonicalVideo.getObjectKey());
    }
}
