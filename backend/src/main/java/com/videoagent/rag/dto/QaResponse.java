package com.videoagent.rag.dto;

import java.util.List;

public record QaResponse(
    String answer,
    List<QaCitation> citations
) {
}
