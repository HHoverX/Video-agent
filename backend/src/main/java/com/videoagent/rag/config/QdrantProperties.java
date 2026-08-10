package com.videoagent.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Qdrant vector-store configuration. Only the REST protocol is used (no extra
 * gRPC client dependency). The collection is a derived vector index, never a
 * business source of truth.
 */
@ConfigurationProperties(prefix = "videoagent.rag.qdrant")
public record QdrantProperties(
    String host,
    int port,
    String collection
) {

    public QdrantProperties {
        host = host == null || host.isBlank() ? "localhost" : host.strip();
        port = port <= 0 ? 6333 : port;
        collection = collection == null || collection.isBlank()
            ? "video_transcript_chunks"
            : collection.strip();
    }

    public String baseUrl() {
        return "http://" + host + ":" + port;
    }
}
