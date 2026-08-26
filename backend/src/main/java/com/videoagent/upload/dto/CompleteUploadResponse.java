package com.videoagent.upload.dto;

public record CompleteUploadResponse(
    String uploadId,
    long videoId,
    String status
) {
}
