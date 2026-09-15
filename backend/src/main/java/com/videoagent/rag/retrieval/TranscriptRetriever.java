package com.videoagent.rag.retrieval;

import com.videoagent.rag.config.RagProperties;
import com.videoagent.rag.embedding.EmbeddingProvider;
import com.videoagent.rag.vector.MilvusTranscriptStore;
import com.videoagent.rag.vector.VectorPoint;
import com.videoagent.rag.rerank.TranscriptReranker;
import com.videoagent.telemetry.QaTelemetryContext;
import com.videoagent.telemetry.QaTelemetryRoute;

import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Retrieves relevant transcript chunks for a question in RAG mode. The vector
 * search always filters on userId + videoId, and returns the top-K chunks
 * ordered by hybrid relevance.
 */
@Component
public class TranscriptRetriever {

    private static final Logger log = LoggerFactory.getLogger(TranscriptRetriever.class);

    private final EmbeddingProvider embeddingProvider;
    private final MilvusTranscriptStore transcriptStore;
    private final ReciprocalRankFusion fusion;
    private final TranscriptReranker reranker;
    private final RagProperties properties;

    public TranscriptRetriever(
        EmbeddingProvider embeddingProvider,
        MilvusTranscriptStore transcriptStore,
        ReciprocalRankFusion fusion,
        TranscriptReranker reranker,
        RagProperties properties
    ) {
        this.embeddingProvider = embeddingProvider;
        this.transcriptStore = transcriptStore;
        this.fusion = fusion;
        this.reranker = reranker;
        this.properties = properties;
    }

    public List<RetrievedChunk> retrieve(long userId, long videoId, String question) {
        return retrieve(userId, videoId, question, embeddingProvider.embedQuery(question));
    }

    public List<RetrievedChunk> retrieve(
        long userId,
        long videoId,
        String question,
        QaTelemetryContext telemetryContext,
        QaTelemetryRoute telemetryRoute
    ) {
        return retrieve(
            userId,
            videoId,
            question,
            embeddingProvider.embedQuery(question, telemetryContext, telemetryRoute)
        );
    }

    private List<RetrievedChunk> retrieve(long userId, long videoId, String query, float[] queryVector) {
        List<VectorPoint> dense = transcriptStore.searchDense(
            userId,
            videoId,
            queryVector,
            properties.denseTopK()
        ).stream()
            .filter(hit -> hit.score() >= properties.denseMinimumScore())
            .toList();
        List<LexicalChunk> lexical = transcriptStore.searchLexical(
            userId, videoId, query, properties.lexicalTopK());
        List<HybridCandidate> fused = fusion.fuse(
            videoId, dense, lexical, properties.rrfK(), properties.rrfCandidateLimit());
        List<HybridCandidate> ordered = fused;
        boolean fallback = false;
        if (reranker.enabled() && !fused.isEmpty()) {
            try {
                ordered = reranker.rerank(query, fused);
            } catch (RuntimeException exception) {
                fallback = true;
                log.warn("[userId={}][videoId={}][stage=RERANK][exceptionClass={}] reranker unavailable; using RRF order",
                    userId, videoId, exception.getClass().getSimpleName());
            }
        }
        List<RetrievedChunk> result = ordered.stream()
            .limit(properties.finalEvidenceLimit())
            .map(candidate -> new RetrievedChunk(
                candidate.chunkIndex(), candidate.text(), candidate.startMs(), candidate.endMs(),
                candidate.sourceSegmentIndexes(),
                candidate.rerankScore() == null
                    ? (float) candidate.rrfScore()
                    : candidate.rerankScore().floatValue()
            ))
            .toList();
        log.info("[userId={}][videoId={}][stage=HYBRID_RETRIEVAL][denseCount={}][lexicalCount={}][fusedCount={}][rerankerFallback={}][finalCount={}]",
            userId, videoId, dense.size(), lexical.size(), fused.size(), fallback, result.size());
        return result;
    }
}
