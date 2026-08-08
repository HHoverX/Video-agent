package com.videoagent.analysis.dto;

import java.time.LocalDateTime;

public record AnalysisTaskResponse(
    Long taskId,
    Long videoId,
    String status,
    String stage,
    int progress,
    String message,
    String errorCode,
    String errorMessage,
    LocalDateTime createdAt,
    LocalDateTime startedAt,
    LocalDateTime finishedAt
) {
}
