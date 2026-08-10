package com.videoagent.video.dto;

import java.util.List;

public record VideoPageResponse(
    List<VideoResponse> items,
    long page,
    long size,
    long total,
    long pages
) {
}
