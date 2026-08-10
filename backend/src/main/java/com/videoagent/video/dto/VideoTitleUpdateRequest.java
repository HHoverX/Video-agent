package com.videoagent.video.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VideoTitleUpdateRequest(
    @NotBlank @Size(max = 255) String title
) {
}
