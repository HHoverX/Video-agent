package com.videoagent.asr;

import java.nio.file.Path;

public record AudioSource(Path file, Integer videoDurationSeconds) {

    public AudioSource {
        file = file.toAbsolutePath().normalize();
    }

    public AudioSource(Path file) {
        this(file, null);
    }
}
