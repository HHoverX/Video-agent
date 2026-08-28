package com.videoagent.media;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Owns only generated transport chunks. A short-input set deliberately owns
 * no directory, so closing it can never delete the original analysis WAV.
 */
public final class AudioChunkSet implements AutoCloseable {

    private final List<AudioChunk> chunks;
    private final int frameRate;
    private final Path generatedDirectory;

    AudioChunkSet(List<AudioChunk> chunks, int frameRate, Path generatedDirectory) {
        this.chunks = List.copyOf(chunks);
        this.frameRate = frameRate;
        this.generatedDirectory = generatedDirectory == null
            ? null
            : generatedDirectory.toAbsolutePath().normalize();
        if (this.chunks.isEmpty() || frameRate <= 0) {
            throw new IllegalArgumentException("Audio chunk set is invalid");
        }
    }

    public List<AudioChunk> chunks() {
        return chunks;
    }

    public int frameRate() {
        return frameRate;
    }

    public long startMs(AudioChunk chunk) {
        if (!chunks.contains(chunk)) {
            throw new IllegalArgumentException("Audio chunk does not belong to this set");
        }
        return Math.floorDiv(Math.multiplyExact(chunk.startFrame(), 1_000L), frameRate);
    }

    public Optional<Path> generatedDirectory() {
        return Optional.ofNullable(generatedDirectory);
    }

    @Override
    public void close() {
        if (generatedDirectory == null || !Files.exists(generatedDirectory)) {
            return;
        }
        try (var paths = Files.walk(generatedDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Path normalized = path.toAbsolutePath().normalize();
                if (!normalized.startsWith(generatedDirectory)) {
                    throw new IOException("Refusing to delete path outside audio chunk directory: " + normalized);
                }
                Files.deleteIfExists(normalized);
            }
        } catch (IOException exception) {
            throw new VideoAgentException(
                ErrorCode.MEDIA_TEMP_FILE_ERROR,
                "ASR 音频分片临时文件清理失败",
                exception
            );
        }
    }
}
