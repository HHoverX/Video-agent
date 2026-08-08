package com.videoagent.media;

import java.nio.file.Path;

public interface MediaProcessor {

    AudioExtractResult extractAudio(Path videoFile, Path audioFile);
}
