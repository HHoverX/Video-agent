package com.videoagent.rag.qa;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record VideoQaAiResponse(
    @JsonProperty("answer") String answer,
    @JsonProperty("citationIndexes") List<Integer> citationIndexes
) {
    public VideoQaAiResponse {
        citationIndexes = citationIndexes == null ? List.of() : List.copyOf(citationIndexes);
    }
}
