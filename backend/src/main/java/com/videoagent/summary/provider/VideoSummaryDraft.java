package com.videoagent.summary.provider;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Temporary LLM contract. Evidence IDs are resolved to authoritative timestamps
 * before persistence and must never be exposed through the API.
 */
public record VideoSummaryDraft(
    @JsonProperty(required = true) String overview,
    @JsonProperty(required = true) List<Chapter> chapters,
    @JsonProperty(required = true) List<KeyPoint> keyPoints
) {
    public VideoSummaryDraft {
        chapters = chapters == null ? List.of() : List.copyOf(chapters);
        keyPoints = keyPoints == null ? List.of() : List.copyOf(keyPoints);
    }

    public record Chapter(
        @JsonProperty(required = true) String title,
        @JsonProperty(required = true) String summary,
        @JsonProperty(required = true) String startEvidenceId,
        @JsonProperty(required = true) String endEvidenceId
    ) {
    }

    public record KeyPoint(
        @JsonProperty(required = true) String content,
        @JsonProperty(required = true) String startEvidenceId,
        @JsonProperty(required = true) String endEvidenceId
    ) {
    }
}
