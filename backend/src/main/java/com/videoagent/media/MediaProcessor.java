package com.videoagent.media;

import java.nio.file.Path;
import java.util.OptionalInt;

public interface MediaProcessor {

    AudioExtractResult extractAudio(Path videoFile, Path audioFile);

    OptionalInt probeDurationSeconds(Path videoFile);
}
