package com.videoagent.agent.qa;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record AgenticQaAiResponse(
    @JsonProperty("answer") String answer,
    @JsonProperty("citationEvidenceIds") List<String> citationEvidenceIds
) {
    public AgenticQaAiResponse {
        citationEvidenceIds = citationEvidenceIds == null
            ? List.of()
            : List.copyOf(citationEvidenceIds);
    }
}
