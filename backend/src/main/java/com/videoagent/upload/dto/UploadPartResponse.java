package com.videoagent.upload.dto;

public record UploadPartResponse(
    int partNumber,
    long size,
    String etag,
    String sha256
) {
}
