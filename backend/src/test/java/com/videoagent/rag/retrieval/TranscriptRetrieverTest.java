package com.videoagent.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.rag.config.RagProperties;
import com.videoagent.rag.embedding.EmbeddingProvider;
import com.videoagent.rag.vector.QdrantVectorStore;
import com.videoagent.rag.vector.VectorPoint;
import com.videoagent.rag.rerank.TranscriptReranker;
import com.videoagent.telemetry.QaTelemetryContext;
import com.videoagent.telemetry.QaTelemetryRoute;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

class TranscriptRetrieverTest {

    private final EmbeddingProvider embeddingProvider = mock(EmbeddingProvider.class);
    private final QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
    private final LexicalTranscriptStore lexicalStore = mock(LexicalTranscriptStore.class);
    private final TranscriptReranker reranker = mock(TranscriptReranker.class);
    private final RagProperties properties = new RagProperties(1000, 200, 1, 5, 0.0f);
    private TranscriptRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new TranscriptRetriever(
            embeddingProvider, vectorStore, lexicalStore, new ReciprocalRankFusion(), reranker, properties);
    }

    @Test
    void shouldEmbedQueryThenSearchWithUserAndVideoFilter() {
        when(embeddingProvider.embedQuery("question")).thenReturn(new float[384]);
        when(vectorStore.search(1L, 7L, new float[384], 5)).thenReturn(List.of(
            VectorPoint.retrieved(2, "chunk2", 4000, 6000, 0.9f),
            VectorPoint.retrieved(0, "chunk0", 0, 2000, 0.7f)
        ));

        List<RetrievedChunk> chunks = retriever.retrieve(1L, 7L, "question");

        assertThat(chunks).hasSize(2);
        assertThat(chunks).extracting(RetrievedChunk::chunkIndex).containsExactly(2, 0);
        verify(embeddingProvider).embedQuery("question");
        verify(vectorStore).search(1L, 7L, new float[384], 5);
    }

    @Test
    void shouldReturnEmptyWhenNoHits() {
        when(embeddingProvider.embedQuery("nothing")).thenReturn(new float[384]);
        when(vectorStore.search(1L, 7L, new float[384], 5)).thenReturn(List.of());

        List<RetrievedChunk> chunks = retriever.retrieve(1L, 7L, "nothing");

        assertThat(chunks).isEmpty();
    }

    @Test
    void shouldPassQaTelemetryContextToQueryEmbedding() {
        QaTelemetryContext context = new QaTelemetryContext("request-1", 7L, 3L);
        when(embeddingProvider.embedQuery("question", context, QaTelemetryRoute.BASIC_RAG))
            .thenReturn(new float[384]);
        when(vectorStore.search(1L, 7L, new float[384], 5)).thenReturn(List.of());

        retriever.retrieve(1L, 7L, "question", context, QaTelemetryRoute.BASIC_RAG);

        verify(embeddingProvider).embedQuery("question", context, QaTelemetryRoute.BASIC_RAG);
    }

    @Test
    void shouldDropHitsBelowConfiguredEvidenceScore() {
        TranscriptRetriever thresholdRetriever = new TranscriptRetriever(
            embeddingProvider, vectorStore, lexicalStore, new ReciprocalRankFusion(), reranker,
            new RagProperties(1000, 200, 1, 5, 0.75f)
        );
        when(embeddingProvider.embedQuery("question")).thenReturn(new float[384]);
        when(vectorStore.search(1L, 7L, new float[384], 5)).thenReturn(List.of(
            VectorPoint.retrieved(2, "strong", 4000, 6000, 0.90f),
            VectorPoint.retrieved(0, "weak", 0, 2000, 0.74f)
        ));

        List<RetrievedChunk> chunks = thresholdRetriever.retrieve(1L, 7L, "question");

        assertThat(chunks).extracting(RetrievedChunk::text).containsExactly("strong");
    }

    @Test
    void shouldFallbackToRrfOrderWhenRerankerFailsAndKeepLexicalOnlyCandidate() {
        RagProperties hybrid = new RagProperties(1000, 200, 1, 15, 0.0f, 15, 60, 15, 3,
            new RagProperties.Reranker(true, "http://reranker", "", "model", java.time.Duration.ofSeconds(1)));
        TranscriptRetriever hybridRetriever = new TranscriptRetriever(
            embeddingProvider, vectorStore, lexicalStore, new ReciprocalRankFusion(), reranker, hybrid);
        when(embeddingProvider.embedQuery("exact term")).thenReturn(new float[384]);
        when(vectorStore.search(1L, 7L, new float[384], 15)).thenReturn(List.of(
            VectorPoint.retrieved(9L, 0, "A", 0, 1000, List.of(0), 0.9f),
            VectorPoint.retrieved(9L, 1, "B", 1000, 2000, List.of(1), 0.8f)
        ));
        when(lexicalStore.search(1L, 7L, "exact term", 15)).thenReturn(List.of(
            new LexicalChunk(ChunkIdentity.of(7L, 9L, 0), 0, "A", 0, 1000, List.of(0), 8.0),
            new LexicalChunk(ChunkIdentity.of(7L, 9L, 2), 2, "D", 2000, 3000, List.of(2), 7.0)
        ));
        when(reranker.enabled()).thenReturn(true);
        when(reranker.rerank(any(), any())).thenThrow(new IllegalStateException("unavailable"));

        List<RetrievedChunk> result = hybridRetriever.retrieve(1L, 7L, "exact term");

        assertThat(result).extracting(RetrievedChunk::text).containsExactly("A", "B", "D");
    }
}
