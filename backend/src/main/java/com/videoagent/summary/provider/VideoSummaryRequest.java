package com.videoagent.summary.provider;

import com.videoagent.asr.TranscriptSegment;
import com.videoagent.telemetry.AnalysisTelemetryContext;

import java.util.List;

public record VideoSummaryRequest(
    long videoId,
    long taskId,
    List<TranscriptSegment> transcriptSegments,
    AnalysisTelemetryContext telemetryContext
) {
    public VideoSummaryRequest {
        transcriptSegments = transcriptSegments == null
            ? List.of()
            : List.copyOf(transcriptSegments);
        telemetryContext = telemetryContext == null
            ? new AnalysisTelemetryContext(taskId, videoId, null, null)
            : telemetryContext;
    }

    public VideoSummaryRequest(long videoId, long taskId, List<TranscriptSegment> transcriptSegments) {
        this(videoId, taskId, transcriptSegments, null);
    }
}
