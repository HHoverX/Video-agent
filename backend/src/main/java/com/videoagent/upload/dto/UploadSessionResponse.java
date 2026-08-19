package com.videoagent.upload.dto;

import java.time.LocalDateTime;
import java.util.List;

public record UploadSessionResponse(
    String uploadId,
    String fileName,
    String title,
    long fileSize,
    String contentType,
    long chunkSize,
    int totalParts,
    String status,
    LocalDateTime expiresAt,
    long uploadedBytes,
    List<UploadPartResponse> completedParts,
    int maxConcurrency,
    Long videoId,
    Long analysisTaskId,
    String lastError
) {
}
