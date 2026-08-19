package com.videoagent.upload.dto;

public record CompleteUploadResponse(
    String uploadId,
    long videoId,
    long analysisTaskId,
    String status
) {
}
