package com.videoagent.rag.retrieval;

import com.videoagent.rag.config.RagProperties;
import com.videoagent.rag.embedding.EmbeddingProvider;
import com.videoagent.rag.vector.QdrantVectorStore;
import com.videoagent.rag.vector.VectorPoint;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Retrieves relevant transcript chunks for a question in RAG mode. The vector
 * search always filters on userId + videoId, and returns the top-K chunks
 * ordered by relevance. No reranking or hybrid search in this milestone.
 */
@Component
public class TranscriptRetriever {

    private final EmbeddingProvider embeddingProvider;
    private final QdrantVectorStore vectorStore;
    private final RagProperties properties;

    public TranscriptRetriever(
        EmbeddingProvider embeddingProvider,
        QdrantVectorStore vectorStore,
        RagProperties properties
    ) {
        this.embeddingProvider = embeddingProvider;
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    public List<RetrievedChunk> retrieve(long userId, long videoId, String question) {
        float[] queryVector = embeddingProvider.embedQuery(question);
        List<VectorPoint> hits = vectorStore.search(
            userId,
            videoId,
            queryVector,
            properties.topK()
        );
        return hits.stream()
            .map(hit -> new RetrievedChunk(
                hit.chunkIndex(),
                hit.text(),
                hit.startMs(),
                hit.endMs(),
                hit.sourceSegmentIndexes(),
                hit.score()
            ))
            .toList();
    }
}
