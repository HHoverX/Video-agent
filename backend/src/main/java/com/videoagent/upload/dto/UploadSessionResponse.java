package com.videoagent.upload.dto;

import java.time.LocalDateTime;
import java.util.List;

public record UploadSessionResponse(
    String uploadId,
    boolean deduplicated,
    String fileName,
    String title,
    long fileSize,
    String contentType,
    long chunkSize,
    int totalParts,
    int partNumberBase,
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
