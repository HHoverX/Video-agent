package com.videoagent.media;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class MediaWorkspace implements AutoCloseable {

    private final Path root;
    private final Path directory;
    private final Path videoFile;
    private final Path audioFile;

    MediaWorkspace(Path root, Path directory) {
        this.root = root.toAbsolutePath().normalize();
        this.directory = directory.toAbsolutePath().normalize();
        if (this.directory.equals(this.root) || !this.directory.startsWith(this.root)) {
            throw new VideoAgentException(ErrorCode.MEDIA_TEMP_FILE_ERROR, "临时媒体目录越界");
        }
        this.videoFile = resolve("source.mp4");
        this.audioFile = resolve("audio.wav");
    }

    public Path directory() {
        return directory;
    }

    public Path videoFile() {
        return videoFile;
    }

    public Path audioFile() {
        return audioFile;
    }

    private Path resolve(String generatedFilename) {
        Path path = directory.resolve(generatedFilename).toAbsolutePath().normalize();
        if (!path.startsWith(directory)) {
            throw new VideoAgentException(ErrorCode.MEDIA_TEMP_FILE_ERROR, "临时媒体文件路径越界");
        }
        return path;
    }

    @Override
    public void close() {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            List<Path> deletionOrder = paths
                .sorted(Comparator.reverseOrder())
                .toList();
            for (Path path : deletionOrder) {
                Path normalized = path.toAbsolutePath().normalize();
                if (!normalized.startsWith(directory)) {
                    throw new IOException("Refusing to delete path outside media workspace: " + normalized);
                }
                Files.deleteIfExists(normalized);
            }
        } catch (IOException exception) {
            throw new VideoAgentException(
                ErrorCode.MEDIA_TEMP_FILE_ERROR,
                "临时媒体文件清理失败",
                exception
            );
        }
    }
}
