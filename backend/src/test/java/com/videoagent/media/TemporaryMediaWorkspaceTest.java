package com.videoagent.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

class TemporaryMediaWorkspaceTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void shouldKeepGeneratedPathsInsideTaskDirectoryAndCleanEverything() throws Exception {
        Path root = tempDirectory.resolve("media-root");
        TemporaryMediaWorkspace factory = new TemporaryMediaWorkspace(properties(root));
        Path taskDirectory;

        try (MediaWorkspace workspace = factory.create(42L)) {
            taskDirectory = workspace.directory();
            assertThat(taskDirectory).startsWith(root.toAbsolutePath().normalize());
            assertThat(workspace.videoFile()).hasFileName("source.mp4");
            assertThat(workspace.videoFile().getParent()).isEqualTo(taskDirectory);
            assertThat(workspace.audioFile()).hasFileName("audio.wav");
            assertThat(workspace.audioFile().getParent()).isEqualTo(taskDirectory);
            Files.writeString(workspace.videoFile(), "video");
            Files.writeString(workspace.audioFile(), "audio");
            Files.writeString(taskDirectory.resolve("ffmpeg-test.stderr"), "diagnostic");
        }

        assertThat(taskDirectory).doesNotExist();
        assertThat(root).exists();
        try (var remaining = Files.list(root)) {
            assertThat(remaining).isEmpty();
        }
    }

    private MediaProperties properties(Path root) {
        return new MediaProperties("ffmpeg", Duration.ofSeconds(30), root, 4_000);
    }
}
