package com.videoagent.video.service;

public record ValidatedVideoFile(
    String title,
    String originalFilename,
    String contentType,
    long size
) {
}
