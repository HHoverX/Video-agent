package com.videoagent.summary.dto;

import com.videoagent.summary.entity.VideoKeyPointEntity;

public record VideoKeyPointResponse(
    int pointIndex,
    String content,
    long startMs,
    long endMs
) {
    public static VideoKeyPointResponse from(VideoKeyPointEntity entity) {
        return new VideoKeyPointResponse(
            entity.getPointIndex(),
            entity.getContent(),
            entity.getStartMs(),
            entity.getEndMs()
        );
    }
}
