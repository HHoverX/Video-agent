package com.videoagent.asr;

import java.util.List;

public record TranscriptionResult(List<TranscriptSegment> segments) {

    public TranscriptionResult {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }
}
