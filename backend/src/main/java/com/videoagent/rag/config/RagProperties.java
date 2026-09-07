package com.videoagent.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

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
    Integer denseTopK,
    Float denseMinimumScore,
    Integer lexicalTopK,
    Integer rrfK,
    Integer rrfCandidateLimit,
    Integer finalEvidenceLimit,
    Reranker reranker
) {

    @ConstructorBinding
    public RagProperties {
        directContextMaxChars = directContextMaxChars <= 0 ? 8_000 : directContextMaxChars;
        chunkMaxChars = chunkMaxChars <= 0 ? 2_000 : chunkMaxChars;
        chunkOverlapSegments = chunkOverlapSegments < 0 ? 1 : chunkOverlapSegments;
        denseTopK = denseTopK == null || denseTopK <= 0 ? 15 : denseTopK;
        denseMinimumScore = denseMinimumScore == null ? 0.0f : denseMinimumScore;
        lexicalTopK = lexicalTopK == null || lexicalTopK <= 0 ? 15 : lexicalTopK;
        rrfK = rrfK == null || rrfK <= 0 ? 60 : rrfK;
        rrfCandidateLimit = rrfCandidateLimit == null || rrfCandidateLimit <= 0 ? 15 : rrfCandidateLimit;
        finalEvidenceLimit = finalEvidenceLimit == null || finalEvidenceLimit <= 0 ? 5 : finalEvidenceLimit;
        reranker = reranker == null ? new Reranker(null, null, null, null, null) : reranker;
        if (denseMinimumScore < 0 || denseMinimumScore > 1) {
            throw new IllegalArgumentException("RAG_DENSE_MINIMUM_SCORE must be between 0 and 1");
        }
        if (finalEvidenceLimit > rrfCandidateLimit) {
            throw new IllegalArgumentException("RAG_FINAL_EVIDENCE_LIMIT must not exceed RAG_RRF_CANDIDATE_LIMIT");
        }
    }

    public RagProperties(int directContextMaxChars, int chunkMaxChars, int chunkOverlapSegments,
                         int denseTopK, float denseMinimumScore) {
        this(directContextMaxChars, chunkMaxChars, chunkOverlapSegments, denseTopK, denseMinimumScore,
            null, null, null, null, null);
    }

    public record Reranker(Boolean enabled, String baseUrl, String apiKey, String model, Duration timeout) {
        public Reranker {
            enabled = enabled != null && enabled;
            baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
            apiKey = apiKey == null ? "" : apiKey.strip();
            model = model == null ? "" : model.strip();
            timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("RAG_RERANKER_TIMEOUT must be positive");
            }
            if (enabled && (baseUrl.isBlank() || model.isBlank())) {
                throw new IllegalArgumentException("Enabled RAG reranker requires base-url and model");
            }
        }
    }

}
