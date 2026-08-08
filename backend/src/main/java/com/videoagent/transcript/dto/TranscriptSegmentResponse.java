package com.videoagent.transcript.dto;

import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;

public record TranscriptSegmentResponse(long startMs, long endMs, String text) {

    public static TranscriptSegmentResponse from(VideoTranscriptSegmentEntity entity) {
        return new TranscriptSegmentResponse(entity.getStartMs(), entity.getEndMs(), entity.getText());
    }
}
