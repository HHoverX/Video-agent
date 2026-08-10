package com.videoagent.rag.embedding;

import java.util.List;

/**
 * Abstraction over embedding providers. Separate methods for documents and
 * query so providers can apply different normalization if needed. The concrete
 * implementation is chosen by configuration; the mock is deterministic so tests
 * can rely on stable vectors.
 */
public interface EmbeddingProvider {

    String providerName();

    int dimension();

    List<float[]> embedDocuments(List<String> texts);

    float[] embedQuery(String text);
}
