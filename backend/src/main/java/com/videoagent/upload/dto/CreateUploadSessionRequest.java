package com.videoagent.upload.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateUploadSessionRequest(
    @NotBlank @Size(max = 255) String fileName,
    @NotBlank @Size(max = 255) String title,
    @NotNull @Positive Long fileSize,
    @NotBlank @Size(max = 100) String contentType,
    @Positive Long chunkSize,
    @Size(min = 64, max = 64) String sha256
) {
}
