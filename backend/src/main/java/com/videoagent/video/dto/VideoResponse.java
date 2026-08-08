package com.videoagent.video.dto;

import com.videoagent.video.entity.VideoEntity;

import java.time.LocalDateTime;

public record VideoResponse(
    Long id,
    String title,
    String originalFilename,
    long fileSize,
    Integer durationSeconds,
    String mimeType,
    String status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static VideoResponse from(VideoEntity entity) {
        return new VideoResponse(
            entity.getId(),
            entity.getTitle(),
            entity.getOriginalFilename(),
            entity.getFileSize(),
            entity.getDurationSeconds(),
            entity.getMimeType(),
            entity.getStatus(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
