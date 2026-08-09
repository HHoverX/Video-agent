package com.videoagent.analysis.dto;

public record AnalysisProgressEventResponse(
    Long taskId,
    Long videoId,
    String status,
    String stage,
    int progress,
    String message,
    String errorCode,
    String errorMessage
) {

    public static AnalysisProgressEventResponse from(AnalysisTaskResponse task) {
        return new AnalysisProgressEventResponse(
            task.taskId(),
            task.videoId(),
            task.status(),
            task.stage(),
            task.progress(),
            task.message(),
            task.errorCode(),
            task.errorMessage()
        );
    }

    public boolean terminal() {
        return "SUCCESS".equals(status) || "FAILED".equals(status);
    }
}
