package com.videoagent.media;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class TemporaryMediaWorkspace {

    private final Path root;

    public TemporaryMediaWorkspace(MediaProperties properties) {
        this.root = properties.tempRoot().toAbsolutePath().normalize();
    }

    public MediaWorkspace create(long taskId) {
        try {
            Files.createDirectories(root);
            Path directory = Files.createTempDirectory(root, "analysis-" + taskId + "-");
            return new MediaWorkspace(root, directory);
        } catch (IOException exception) {
            throw new VideoAgentException(
                ErrorCode.MEDIA_TEMP_FILE_ERROR,
                "无法创建临时媒体目录",
                exception
            );
        }
    }

    public Path root() {
        return root;
    }
}
