package com.videoagent.asr;

public record TranscriptSegment(long startMs, long endMs, String text) {

    public TranscriptSegment {
        if (startMs < 0 || endMs <= startMs) {
            throw new IllegalArgumentException("Transcript segment time range is invalid");
        }
        text = text == null ? "" : text.strip();
        if (text.isEmpty() || text.length() > 2_000) {
            throw new IllegalArgumentException("Transcript segment text is invalid");
        }
    }
}
