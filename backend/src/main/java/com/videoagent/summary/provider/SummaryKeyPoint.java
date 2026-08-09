package com.videoagent.summary.provider;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SummaryKeyPoint(
    @JsonProperty(required = true) String content,
    @JsonProperty(required = true) long startMs,
    @JsonProperty(required = true) long endMs
) {
}
