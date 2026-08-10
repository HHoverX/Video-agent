package com.videoagent.rag.qa;

import java.util.List;

/**
 * Grounded video QA. The LLM never fabricates timestamps: it returns an answer
 * plus the indexes of the context items it actually used, and the backend maps
 * those indexes back to real transcript segment / chunk metadata. If the
 * context is insufficient, the answer signals it.
 */
public record VideoQaResult(
    String answer,
    List<Integer> citationIndexes
) {
    public VideoQaResult {
        citationIndexes = citationIndexes == null ? List.of() : List.copyOf(citationIndexes);
    }
}
