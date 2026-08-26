package com.videoagent.video.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "videoagent.playback")
public record VideoPlaybackProperties(Duration presignTtl) {

    public VideoPlaybackProperties {
        if (presignTtl == null || presignTtl.isZero() || presignTtl.isNegative()) {
            throw new IllegalArgumentException("playback presign TTL must be positive");
        }
    }
}
