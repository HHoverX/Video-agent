package com.videoagent.upload.dto;

import jakarta.validation.constraints.Size;

public record CompleteUploadPartRequest(
    @Size(min = 64, max = 64) String sha256
) {
}
