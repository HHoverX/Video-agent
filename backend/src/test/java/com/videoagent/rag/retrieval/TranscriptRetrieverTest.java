package com.videoagent.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.rag.config.RagProperties;
import com.videoagent.rag.embedding.EmbeddingProvider;
import com.videoagent.rag.vector.MilvusTranscriptStore;
import com.videoagent.rag.vector.VectorPoint;
import com.videoagent.telemetry.QaTelemetryContext;
import com.videoagent.telemetry.QaTelemetryRoute;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

class TranscriptRetrieverTest {

    private final EmbeddingProvider embeddingProvider = mock(EmbeddingProvider.class);
    private final MilvusTranscriptStore transcriptStore = mock(MilvusTranscriptStore.class);
    private final RagProperties properties = new RagProperties(200, 1, 5, 0.0f);
    private TranscriptRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new TranscriptRetriever(
            embeddingProvider, transcriptStore, new ReciprocalRankFusion(), properties);
    }

    @Test
    void shouldEmbedQueryThenSearchWithUserAndVideoFilter() {
        float[] vector = new float[384];
        when(embeddingProvider.embedQuery("question")).thenReturn(vector);
        when(transcriptStore.searchDense(1L, 7L, vector, 5)).thenReturn(List.of(
            VectorPoint.retrieved(2, "chunk2", 4000, 6000, 0.9f),
            VectorPoint.retrieved(0, "chunk0", 0, 2000, 0.7f)
        ));

        List<RetrievedChunk> chunks = retriever.retrieve(1L, 7L, "question");

        assertThat(chunks).extracting(RetrievedChunk::chunkIndex).containsExactly(2, 0);
        verify(embeddingProvider).embedQuery("question");
        verify(transcriptStore).searchDense(1L, 7L, vector, 5);
        verify(transcriptStore).searchLexical(1L, 7L, "question", 15);
    }

    @Test
    void shouldPassQaTelemetryContextToQueryEmbedding() {
        QaTelemetryContext context = new QaTelemetryContext("request-1", 7L, 3L);
        float[] vector = new float[384];
        when(embeddingProvider.embedQuery("question", context, QaTelemetryRoute.BASIC_RAG))
            .thenReturn(vector);

        retriever.retrieve(1L, 7L, "question", context, QaTelemetryRoute.BASIC_RAG);

        verify(embeddingProvider).embedQuery("question", context, QaTelemetryRoute.BASIC_RAG);
    }

    @Test
    void shouldDropDenseHitsBelowConfiguredScore() {
        TranscriptRetriever thresholdRetriever = new TranscriptRetriever(
            embeddingProvider,
            transcriptStore,
            new ReciprocalRankFusion(),
            new RagProperties(200, 1, 5, 0.75f)
        );
        float[] vector = new float[384];
        when(embeddingProvider.embedQuery("question")).thenReturn(vector);
        when(transcriptStore.searchDense(1L, 7L, vector, 5)).thenReturn(List.of(
            VectorPoint.retrieved(2, "strong", 4000, 6000, 0.90f),
            VectorPoint.retrieved(0, "weak", 0, 2000, 0.74f)
        ));

        List<RetrievedChunk> chunks = thresholdRetriever.retrieve(1L, 7L, "question");

        assertThat(chunks).extracting(RetrievedChunk::text).containsExactly("strong");
    }

    @Test
    void shouldFuseDenseAndBm25ByRrfThenApplyFinalEvidenceLimit() {
        RagProperties limited = new RagProperties(200, 1, 15, 0.0f, 15, 60, 2);
        TranscriptRetriever limitedRetriever = new TranscriptRetriever(
            embeddingProvider, transcriptStore, new ReciprocalRankFusion(), limited);
        float[] vector = new float[384];
        when(embeddingProvider.embedQuery("exact term")).thenReturn(vector);
        when(transcriptStore.searchDense(1L, 7L, vector, 15)).thenReturn(List.of(
            point(0, "A", 0.9f), point(1, "B", 0.8f), point(2, "C", 0.7f)
        ));
        when(transcriptStore.searchLexical(1L, 7L, "exact term", 15)).thenReturn(List.of(
            lexical(2, "C", 9.0), lexical(3, "D", 8.0), lexical(0, "A", 7.0)
        ));

        List<RetrievedChunk> result = limitedRetriever.retrieve(1L, 7L, "exact term");

        assertThat(result).extracting(RetrievedChunk::text).containsExactly("A", "C");
        assertThat(result).allSatisfy(chunk ->
            assertThat(chunk.score()).isEqualTo((float) (1.0 / 61 + 1.0 / 63)));
    }

    private VectorPoint point(int index, String text, float score) {
        return VectorPoint.retrieved(9L, index, text, index * 1000L, index * 1000L + 900,
            List.of(index), score);
    }

    private LexicalChunk lexical(int index, String text, double score) {
        return new LexicalChunk(ChunkIdentity.of(7L, 9L, index), index, text,
            index * 1000L, index * 1000L + 900, List.of(index), score);
    }
}
