package com.videoagent.summary.provider;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record VideoSummaryResult(
    @JsonProperty(required = true) String overview,
    @JsonProperty(required = true) List<SummaryChapter> chapters,
    @JsonProperty(required = true) List<SummaryKeyPoint> keyPoints
) {
    public VideoSummaryResult {
        chapters = chapters == null ? List.of() : List.copyOf(chapters);
        keyPoints = keyPoints == null ? List.of() : List.copyOf(keyPoints);
    }
}
