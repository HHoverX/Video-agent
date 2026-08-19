package com.videoagent.upload.service;

import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.upload.dto.CompleteUploadResponse;

import org.springframework.stereotype.Service;

@Service
public class UploadCompletionService {

    private final UploadCompletionTransaction transaction;
    private final UploadFailureRecorder failureRecorder;

    public UploadCompletionService(
        UploadCompletionTransaction transaction,
        UploadFailureRecorder failureRecorder
    ) {
        this.transaction = transaction;
        this.failureRecorder = failureRecorder;
    }

    public CompleteUploadResponse complete(long userId, String uploadId) {
        try {
            return transaction.complete(userId, uploadId);
        } catch (VideoAgentException exception) {
            failureRecorder.recordRetryableCompletionFailure(userId, uploadId, exception.getMessage());
            throw exception;
        } catch (RuntimeException exception) {
            failureRecorder.recordRetryableCompletionFailure(userId, uploadId, exception.getMessage());
            throw exception;
        }
    }
}
