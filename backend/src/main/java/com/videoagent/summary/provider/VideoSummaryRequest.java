package com.videoagent.summary.provider;

import com.videoagent.asr.TranscriptSegment;

import java.util.List;

public record VideoSummaryRequest(
    long videoId,
    long taskId,
    List<TranscriptSegment> transcriptSegments
) {
    public VideoSummaryRequest {
        transcriptSegments = transcriptSegments == null
            ? List.of()
            : List.copyOf(transcriptSegments);
    }
}
