package com.videoagent.upload.service;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.storage.ObjectStorageService;
import com.videoagent.storage.StoredObject;
import com.videoagent.upload.entity.VideoUploadSessionEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class UploadPartStateService {

    private static final Logger log = LoggerFactory.getLogger(UploadPartStateService.class);

    private final UploadPartBitmapStore bitmapStore;
    private final ObjectStorageService storageService;

    public UploadPartStateService(
        UploadPartBitmapStore bitmapStore,
        ObjectStorageService storageService
    ) {
        this.bitmapStore = bitmapStore;
        this.storageService = storageService;
    }

    public List<Integer> completedPartNumbers(VideoUploadSessionEntity session) {
        int firstPartNumber = UploadKeyPolicy.firstPartNumber(session.getTempPrefix());
        try {
            if (bitmapStore.exists(session.getId())) {
                return bitmapStore.readCompleted(session.getId(), firstPartNumber, session.getTotalParts());
            }
        } catch (DataAccessException exception) {
            log.warn("[uploadId={}][stage=BITMAP_READ][exceptionClass={}] Redis unavailable; scanning MinIO",
                session.getId(), exception.getClass().getSimpleName());
            return scanMinio(session);
        }

        List<Integer> completed = scanMinio(session);
        try {
            bitmapStore.rebuild(session.getId(), completed, session.getExpiresAt());
        } catch (DataAccessException exception) {
            log.warn("[uploadId={}][stage=BITMAP_REFILL][exceptionClass={}] bitmap refill failed",
                session.getId(), exception.getClass().getSimpleName());
        }
        return completed;
    }

    public void requirePossiblyComplete(VideoUploadSessionEntity session) {
        requirePossiblyComplete(
            session.getId(), session.getTempPrefix(), session.getFileSize(), session.getChunkSize(),
            session.getTotalParts(), session.getExpiresAt()
        );
    }

    public void requirePossiblyComplete(UploadCompletionAttempt attempt) {
        requirePossiblyComplete(
            attempt.uploadId(), attempt.tempPrefix(), attempt.fileSize(), attempt.chunkSize(),
            attempt.totalParts(), attempt.expiresAt()
        );
    }

    private void requirePossiblyComplete(
        String uploadId,
        String tempPrefix,
        long fileSize,
        long chunkSize,
        int totalParts,
        java.time.LocalDateTime expiresAt
    ) {
        try {
            if (!bitmapStore.exists(uploadId)) {
                bitmapStore.rebuild(
                    uploadId,
                    scanMinio(uploadId, tempPrefix, fileSize, chunkSize, totalParts),
                    expiresAt
                );
            }
            if (bitmapStore.count(uploadId) < totalParts) {
                throw new VideoAgentException(ErrorCode.UPLOAD_PART_INVALID, "仍有分片尚未上传完成");
            }
        } catch (DataAccessException exception) {
            log.warn("[uploadId={}][stage=BITMAP_COMPLETE][exceptionClass={}] Redis unavailable; using MinIO validation",
                uploadId, exception.getClass().getSimpleName());
        }
    }

    public boolean markCompleted(VideoUploadSessionEntity session, int partNumber) {
        return bitmapStore.markCompleted(session.getId(), partNumber, session.getExpiresAt());
    }

    public void deleteBestEffort(String uploadId) {
        try {
            bitmapStore.delete(uploadId);
        } catch (DataAccessException exception) {
            log.warn("[uploadId={}][stage=BITMAP_DELETE][exceptionClass={}] bitmap cleanup will rely on TTL",
                uploadId, exception.getClass().getSimpleName());
        }
    }

    private List<Integer> scanMinio(VideoUploadSessionEntity session) {
        return scanMinio(
            session.getId(), session.getTempPrefix(), session.getFileSize(), session.getChunkSize(),
            session.getTotalParts()
        );
    }

    private List<Integer> scanMinio(
        String uploadId,
        String tempPrefix,
        long fileSize,
        long chunkSize,
        int totalParts
    ) {
        int firstPartNumber = UploadKeyPolicy.firstPartNumber(tempPrefix);
        List<Integer> completed = new ArrayList<>(totalParts);
        for (int offset = 0; offset < totalParts; offset++) {
            int partNumber = firstPartNumber + offset;
            String objectKey = UploadKeyPolicy.partObjectKey(tempPrefix, partNumber);
            StoredObject stored = storageService.statObjectIfExists(objectKey);
            if (stored == null) {
                continue;
            }
            long expectedSize = Math.min(chunkSize, fileSize - (long) offset * chunkSize);
            if (stored.size() == expectedSize) {
                completed.add(partNumber);
            } else {
                log.warn("[uploadId={}][partNumber={}][stage=BITMAP_REBUILD] ignored part with unexpected size",
                    uploadId, partNumber);
            }
        }
        return List.copyOf(completed);
    }
}
