package com.videoagent.summary.dto;

import com.videoagent.summary.entity.VideoChapterEntity;

public record VideoChapterResponse(
    int chapterIndex,
    String title,
    String summary,
    long startMs,
    long endMs
) {
    public static VideoChapterResponse from(VideoChapterEntity entity) {
        return new VideoChapterResponse(
            entity.getChapterIndex(),
            entity.getTitle(),
            entity.getSummary(),
            entity.getStartMs(),
            entity.getEndMs()
        );
    }
}
