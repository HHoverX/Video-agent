package com.videoagent.summary.provider;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SummaryChapter(
    @JsonProperty(required = true) String title,
    @JsonProperty(required = true) String summary,
    @JsonProperty(required = true) long startMs,
    @JsonProperty(required = true) long endMs
) {
}
