package com.videoagent.rag.qa;

import java.util.List;

/**
 * A QA request carries the already-selected context: either all transcript
 * segments (DIRECT_CONTEXT) or the top-K retrieved chunks (RAG). The provider
 * answers strictly from this context.
 */
public record VideoQaRequest(
    long videoId,
    String question,
    List<ContextItem> context
) {
    public VideoQaRequest {
        context = context == null ? List.of() : List.copyOf(context);
    }

    public record ContextItem(
        int index,
        String text,
        long startMs,
        long endMs
    ) {
    }
}
