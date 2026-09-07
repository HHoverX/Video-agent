package com.videoagent.upload.dto;

import jakarta.validation.constraints.Pattern;

public record CompleteUploadRequest(
    @Pattern(regexp = "[0-9a-fA-F]{64}") String sha256
) {
}
