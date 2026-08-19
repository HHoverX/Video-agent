package com.videoagent.upload.dto;

import java.time.Instant;

public record UploadPartUrlResponse(
    int partNumber,
    long expectedSize,
    boolean alreadyCompleted,
    String uploadUrl,
    Instant expiresAt
) {
}
