package com.videoagent.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties(prefix = "videoagent.media")
public record MediaProperties(
    String ffmpegPath,
    Duration ffmpegTimeout,
    Path tempRoot,
    Integer stderrMaxChars
) {
    public MediaProperties {
        ffmpegPath = ffmpegPath == null || ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath;
        ffmpegTimeout = ffmpegTimeout == null ? Duration.ofSeconds(30) : ffmpegTimeout;
        tempRoot = tempRoot == null
            ? Path.of(System.getProperty("java.io.tmpdir"), "videoagent-media")
            : tempRoot;
        stderrMaxChars = stderrMaxChars == null || stderrMaxChars < 256 ? 4_000 : stderrMaxChars;
    }
}
