package com.videoagent.upload.service;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.storage.ComposeObjectSource;
import com.videoagent.storage.ObjectStorageService;
import com.videoagent.storage.StoredObject;
import com.videoagent.upload.dto.CompleteUploadRequest;
import com.videoagent.upload.dto.CompleteUploadResponse;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class UploadCompletionService {

    private final UploadCompletionTransaction transaction;
    private final UploadFailureRecorder failureRecorder;
    private final ObjectStorageService storageService;
    private final UploadPartStateService partStateService;

    public UploadCompletionService(
        UploadCompletionTransaction transaction,
        UploadFailureRecorder failureRecorder,
        ObjectStorageService storageService,
        UploadPartStateService partStateService
    ) {
        this.transaction = transaction;
        this.failureRecorder = failureRecorder;
        this.storageService = storageService;
        this.partStateService = partStateService;
    }

    public CompleteUploadResponse complete(long userId, String uploadId, CompleteUploadRequest request) {
        BeginCompletionResult begin = transaction.beginCompletion(userId, uploadId, request);
        if (begin.completedResponse() != null) {
            return begin.completedResponse();
        }

        UploadCompletionAttempt attempt = begin.attempt();
        try {
            performStorageCompletion(attempt);
            return transaction.finalizeCompletion(attempt);
        } catch (RuntimeException exception) {
            failureRecorder.recordRetryableCompletionFailure(
                attempt.userId(), attempt.uploadId(), attempt.completionToken(), exception.getMessage()
            );
            throw exception;
        }
    }

    private void performStorageCompletion(UploadCompletionAttempt attempt) {
        StoredObject existingFinalObject = storageService.statObjectIfExists(attempt.objectKey());
        if (existingFinalObject != null) {
            validateFinalObject(attempt, existingFinalObject);
            return;
        }

        partStateService.requirePossiblyComplete(attempt);
        List<ComposeObjectSource> sources = validateParts(attempt);
        storageService.composeObject(attempt.objectKey(), sources, attempt.contentType());
        validateFinalObject(attempt, storageService.statObject(attempt.objectKey()));
    }

    private List<ComposeObjectSource> validateParts(UploadCompletionAttempt attempt) {
        List<ComposeObjectSource> sources = new ArrayList<>(attempt.totalParts());
        int firstPartNumber = UploadKeyPolicy.firstPartNumber(attempt.tempPrefix());
        for (int offset = 0; offset < attempt.totalParts(); offset++) {
            int partNumber = firstPartNumber + offset;
            String objectKey = UploadKeyPolicy.partObjectKey(attempt.tempPrefix(), partNumber);
            StoredObject stored = storageService.statObjectIfExists(objectKey);
            if (stored == null) {
                throw new VideoAgentException(ErrorCode.UPLOAD_PART_INVALID, "缺少分片 " + partNumber);
            }
            long expectedSize = expectedPartSize(attempt, offset);
            if (stored.size() != expectedSize) {
                throw new VideoAgentException(ErrorCode.UPLOAD_PART_INVALID, "分片 %d 大小不正确".formatted(partNumber));
            }
            sources.add(new ComposeObjectSource(objectKey, stored.etag()));
        }
        return sources;
    }

    private long expectedPartSize(UploadCompletionAttempt attempt, int partOffset) {
        long byteOffset = (long) partOffset * attempt.chunkSize();
        return Math.min(attempt.chunkSize(), attempt.fileSize() - byteOffset);
    }

    private void validateFinalObject(UploadCompletionAttempt attempt, StoredObject completedObject) {
        if (completedObject.size() != attempt.fileSize()) {
            throw new VideoAgentException(
                ErrorCode.UPLOAD_PART_INVALID,
                "合并后文件大小不匹配，期望 %d，实际 %d".formatted(attempt.fileSize(), completedObject.size())
            );
        }
        byte[] header = storageService.readObjectRange(attempt.objectKey(), 0, 12);
        boolean valid = header.length == 12
            && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p';
        if (!valid) {
            throw new VideoAgentException(ErrorCode.VIDEO_FORMAT_NOT_SUPPORTED, "合并文件不是有效的 MP4 文件");
        }
    }
}
