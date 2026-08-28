package com.videoagent.media;

import java.nio.file.Path;

/**
 * A physical range of the source PCM WAV. Source frame positions, rather than
 * rounded durations, are the authoritative transport offsets.
 */
public record AudioChunk(
    Path file,
    int index,
    long startFrame,
    long frameCount
) {

    public AudioChunk {
        file = file.toAbsolutePath().normalize();
        if (index < 0 || startFrame < 0 || frameCount <= 0) {
            throw new IllegalArgumentException("Audio chunk range is invalid");
        }
    }
}
