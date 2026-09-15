package com.videoagent.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "videoagent.rag.milvus")
public record MilvusProperties(
    String uri,
    String token,
    String collection,
    Duration timeout
) {
    public MilvusProperties {
        uri = uri == null || uri.isBlank() ? "http://localhost:19530" : uri;
        token = token == null ? "" : token;
        collection = collection == null || collection.isBlank()
            ? "video_transcript_chunks"
            : collection;
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
    }
}
