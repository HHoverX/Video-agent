package com.videoagent.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG policy configuration. The threshold values are engineering defaults, not
 * theoretically optimal parameters; they are chosen to keep short transcripts
 * in DIRECT_CONTEXT mode and only route genuinely large transcripts to RAG.
 */
@ConfigurationProperties(prefix = "videoagent.rag")
public record RagProperties(
    int directContextMaxChars,
    int chunkMaxChars,
    int chunkOverlapSegments,
    int topK
) {

    public RagProperties {
        directContextMaxChars = directContextMaxChars <= 0 ? 8_000 : directContextMaxChars;
        chunkMaxChars = chunkMaxChars <= 0 ? 2_000 : chunkMaxChars;
        chunkOverlapSegments = chunkOverlapSegments < 0 ? 1 : chunkOverlapSegments;
        topK = topK <= 0 ? 5 : topK;
    }
}
