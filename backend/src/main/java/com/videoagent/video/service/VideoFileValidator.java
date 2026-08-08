package com.videoagent.video.service;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

@Component
public class VideoFileValidator {

    private static final int MP4_HEADER_LENGTH = 12;
    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of("video/mp4", "application/mp4");

    private final VideoUploadProperties properties;

    public VideoFileValidator(VideoUploadProperties properties) {
        this.properties = properties;
    }

    public ValidatedVideoFile validate(MultipartFile file, String requestedTitle) {
        if (file == null || file.isEmpty()) {
            throw new VideoAgentException(ErrorCode.INVALID_REQUEST, "请选择非空 MP4 视频文件");
        }

        if (file.getSize() > properties.maxFileSize().toBytes()) {
            throw new VideoAgentException(
                ErrorCode.VIDEO_FILE_TOO_LARGE,
                "视频文件不能超过 " + properties.maxFileSize().toMegabytes() + " MB"
            );
        }

        String originalFilename = safeFilename(file.getOriginalFilename());
        String lowerFilename = originalFilename.toLowerCase(Locale.ROOT);
        String contentType = file.getContentType() == null
            ? ""
            : file.getContentType().toLowerCase(Locale.ROOT);

        if (!lowerFilename.endsWith(".mp4") || !SUPPORTED_CONTENT_TYPES.contains(contentType) || !hasMp4Signature(file)) {
            throw new VideoAgentException(ErrorCode.VIDEO_FORMAT_NOT_SUPPORTED);
        }

        String title = requestedTitle == null || requestedTitle.isBlank()
            ? originalFilename.substring(0, originalFilename.length() - 4)
            : requestedTitle.trim();
        if (title.isBlank() || title.length() > 255) {
            throw new VideoAgentException(ErrorCode.INVALID_REQUEST, "视频标题长度必须为 1 至 255 个字符");
        }

        return new ValidatedVideoFile(title, originalFilename, contentType, file.getSize());
    }

    private String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new VideoAgentException(ErrorCode.INVALID_REQUEST, "视频文件名不能为空");
        }

        try {
            Path fileNamePath = Path.of(filename).getFileName();
            String safeName = fileNamePath == null ? "" : fileNamePath.toString();
            if (safeName.isBlank() || safeName.length() > 255) {
                throw new VideoAgentException(ErrorCode.INVALID_REQUEST, "视频文件名长度必须为 1 至 255 个字符");
            }
            return safeName;
        } catch (InvalidPathException exception) {
            throw new VideoAgentException(ErrorCode.INVALID_REQUEST, "视频文件名不合法", exception);
        }
    }

    private boolean hasMp4Signature(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            byte[] header = inputStream.readNBytes(MP4_HEADER_LENGTH);
            return header.length == MP4_HEADER_LENGTH
                && header[4] == 'f'
                && header[5] == 't'
                && header[6] == 'y'
                && header[7] == 'p';
        } catch (IOException exception) {
            throw new VideoAgentException(ErrorCode.VIDEO_UPLOAD_FAILED, "无法读取上传的视频文件", exception);
        }
    }
}
