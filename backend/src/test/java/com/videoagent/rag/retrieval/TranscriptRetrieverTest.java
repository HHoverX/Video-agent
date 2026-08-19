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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

class TranscriptRetrieverTest {

    private final EmbeddingProvider embeddingProvider = mock(EmbeddingProvider.class);
    private final QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
    private final RagProperties properties = new RagProperties(1000, 200, 1, 5, 0.0f);
    private TranscriptRetriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new TranscriptRetriever(embeddingProvider, vectorStore, properties);
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
    void shouldDropHitsBelowConfiguredEvidenceScore() {
        TranscriptRetriever thresholdRetriever = new TranscriptRetriever(
            embeddingProvider, vectorStore, new RagProperties(1000, 200, 1, 5, 0.75f)
        );
        when(embeddingProvider.embedQuery("question")).thenReturn(new float[384]);
        when(vectorStore.search(1L, 7L, new float[384], 5)).thenReturn(List.of(
            VectorPoint.retrieved(2, "strong", 4000, 6000, 0.90f),
            VectorPoint.retrieved(0, "weak", 0, 2000, 0.74f)
        ));

        List<RetrievedChunk> chunks = thresholdRetriever.retrieve(1L, 7L, "question");

        assertThat(chunks).extracting(RetrievedChunk::text).containsExactly("strong");
    }
}
