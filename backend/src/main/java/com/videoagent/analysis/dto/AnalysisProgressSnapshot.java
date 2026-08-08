package com.videoagent.analysis.dto;

public record AnalysisProgressSnapshot(
    String status,
    String stage,
    int progress,
    String message
) {
}
