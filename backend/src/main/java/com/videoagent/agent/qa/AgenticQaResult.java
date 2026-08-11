package com.videoagent.agent.qa;

import com.videoagent.agent.evidence.EvidenceItem;

import java.util.List;

public record AgenticQaResult(
    String answer,
    List<String> citationEvidenceIds
) {
    public AgenticQaResult {
        citationEvidenceIds = citationEvidenceIds == null
            ? List.of()
            : List.copyOf(citationEvidenceIds);
    }
}
