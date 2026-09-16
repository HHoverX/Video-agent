package com.videoagent.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** RAG retrieval and chunking configuration. */
@ConfigurationProperties(prefix = "videoagent.rag")
public record RagProperties(
    int chunkTargetTokens,
    int chunkOverlapSegments,
    Integer denseTopK,
    Float denseMinimumScore,
    Integer lexicalTopK,
    Integer rrfK,
    Integer finalEvidenceLimit
) {

    @ConstructorBinding
    public RagProperties {
        if (chunkTargetTokens <= 0) {
            throw new IllegalArgumentException("RAG_CHUNK_TARGET_TOKENS must be greater than 0");
        }
        chunkOverlapSegments = chunkOverlapSegments < 0 ? 1 : chunkOverlapSegments;
        denseTopK = denseTopK == null || denseTopK <= 0 ? 15 : denseTopK;
        denseMinimumScore = denseMinimumScore == null ? 0.0f : denseMinimumScore;
        lexicalTopK = lexicalTopK == null || lexicalTopK <= 0 ? 15 : lexicalTopK;
        rrfK = rrfK == null || rrfK <= 0 ? 60 : rrfK;
        finalEvidenceLimit = finalEvidenceLimit == null || finalEvidenceLimit <= 0 ? 5 : finalEvidenceLimit;
        if (denseMinimumScore < 0 || denseMinimumScore > 1) {
            throw new IllegalArgumentException("RAG_DENSE_MINIMUM_SCORE must be between 0 and 1");
        }
    }

    public RagProperties(int chunkTargetTokens, int chunkOverlapSegments, int denseTopK, float denseMinimumScore) {
        this(chunkTargetTokens, chunkOverlapSegments, denseTopK, denseMinimumScore, null, null, null);
    }
}
