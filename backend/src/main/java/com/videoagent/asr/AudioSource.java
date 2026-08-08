package com.videoagent.asr;

import java.nio.file.Path;

public record AudioSource(Path file) {

    public AudioSource {
        file = file.toAbsolutePath().normalize();
    }
}
