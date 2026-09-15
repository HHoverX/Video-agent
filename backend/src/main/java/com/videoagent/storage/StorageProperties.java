package com.videoagent.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "videoagent.storage")
public record StorageProperties(
    String endpoint,
    String publicEndpoint,
    String uploadEndpoint,
    String accessKey,
    String secretKey,
    String bucket
) {
    public StorageProperties {
        if (publicEndpoint == null || publicEndpoint.isBlank()) {
            publicEndpoint = endpoint;
        }
        if (uploadEndpoint == null || uploadEndpoint.isBlank()) {
            uploadEndpoint = publicEndpoint;
        }
    }
}
