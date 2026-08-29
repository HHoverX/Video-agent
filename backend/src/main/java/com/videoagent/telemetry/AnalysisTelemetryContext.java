package com.videoagent.telemetry;

/**
 * Explicit, identifier-only correlation for AI work performed by one analysis
 * task attempt. It deliberately carries no workflow behavior or user content.
 */
public record AnalysisTelemetryContext(
    Long taskId,
    Long videoId,
    Integer generation,
    Integer retryCount
) {

    public static AnalysisTelemetryContext unavailable() {
        return new AnalysisTelemetryContext(null, null, null, null);
    }
}
