package com.videoagent.summary.dto;

import com.videoagent.summary.entity.VideoSummaryEntity;

import java.time.LocalDateTime;

public record VideoSummaryResponse(
    long taskId,
    String overview,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static VideoSummaryResponse from(VideoSummaryEntity entity) {
        return new VideoSummaryResponse(
            entity.getTaskId(),
            entity.getOverview(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
