package com.videoagent.telemetry;

import java.util.UUID;

/**
 * Identifier-only correlation for one user QA request. It deliberately carries
 * no user content or mutable usage state.
 */
public record QaTelemetryContext(
    String requestId,
    long videoId,
    Long analysisTaskId
) {

    public static QaTelemetryContext newRequest(long videoId) {
        return new QaTelemetryContext(UUID.randomUUID().toString(), videoId, null);
    }

    public QaTelemetryContext withAnalysisTaskId(Long taskId) {
        return new QaTelemetryContext(requestId, videoId, taskId);
    }
}
