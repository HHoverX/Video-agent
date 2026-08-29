package com.videoagent.rag.embedding;

import com.videoagent.telemetry.AnalysisTelemetryContext;
import com.videoagent.telemetry.QaTelemetryContext;
import com.videoagent.telemetry.QaTelemetryRoute;

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

    default List<float[]> embedDocuments(List<String> texts, AnalysisTelemetryContext telemetryContext) {
        return embedDocuments(texts);
    }

    float[] embedQuery(String text);

    default float[] embedQuery(
        String text,
        QaTelemetryContext telemetryContext,
        QaTelemetryRoute telemetryRoute
    ) {
        return embedQuery(text);
    }
}
