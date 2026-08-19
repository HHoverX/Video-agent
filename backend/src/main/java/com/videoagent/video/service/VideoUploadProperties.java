package com.videoagent.video.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

@ConfigurationProperties(prefix = "videoagent.upload")
public record VideoUploadProperties(
    DataSize maxFileSize,
    DataSize defaultChunkSize,
    DataSize minChunkSize,
    DataSize maxChunkSize,
    int maxParts,
    Duration sessionTtl,
    Duration presignTtl,
    int maxClientConcurrency
) {

    public VideoUploadProperties {
        if (maxFileSize == null) {
            maxFileSize = DataSize.ofGigabytes(20);
        }
        defaultChunkSize = defaultChunkSize == null ? DataSize.ofMegabytes(16) : defaultChunkSize;
        minChunkSize = minChunkSize == null ? DataSize.ofMegabytes(5) : minChunkSize;
        maxChunkSize = maxChunkSize == null ? DataSize.ofMegabytes(128) : maxChunkSize;
        maxParts = maxParts <= 0 ? 10_000 : maxParts;
        sessionTtl = sessionTtl == null ? Duration.ofHours(24) : sessionTtl;
        presignTtl = presignTtl == null ? Duration.ofMinutes(15) : presignTtl;
        maxClientConcurrency = maxClientConcurrency <= 0 ? 3 : maxClientConcurrency;
        if (maxFileSize.toBytes() <= 0) {
            throw new IllegalArgumentException("upload max file size must be positive");
        }
        if (minChunkSize.toBytes() <= 0
            || maxChunkSize.compareTo(minChunkSize) < 0
            || defaultChunkSize.compareTo(minChunkSize) < 0
            || defaultChunkSize.compareTo(maxChunkSize) > 0) {
            throw new IllegalArgumentException("upload chunk size bounds are invalid");
        }
        if (maxParts > 10_000) {
            throw new IllegalArgumentException("upload max parts cannot exceed MinIO Compose limit 10000");
        }
        if (sessionTtl.isZero() || sessionTtl.isNegative()
            || presignTtl.isZero() || presignTtl.isNegative()) {
            throw new IllegalArgumentException("upload session and presign TTL must be positive");
        }
    }
}
