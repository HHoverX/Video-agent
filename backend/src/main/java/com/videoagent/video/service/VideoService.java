package com.videoagent.video.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.storage.ObjectStorageService;
import com.videoagent.video.dto.VideoResponse;
import com.videoagent.video.dto.VideoUploadResponse;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class VideoService {

    private static final Logger log = LoggerFactory.getLogger(VideoService.class);
    private static final String UPLOADED_STATUS = "UPLOADED";

    private final VideoRepository videoRepository;
    private final VideoFileValidator fileValidator;
    private final ObjectStorageService storageService;

    public VideoService(
        VideoRepository videoRepository,
        VideoFileValidator fileValidator,
        ObjectStorageService storageService
    ) {
        this.videoRepository = videoRepository;
        this.fileValidator = fileValidator;
        this.storageService = storageService;
    }

    @Transactional
    public VideoUploadResponse upload(MultipartFile file, String requestedTitle) {
        ValidatedVideoFile validatedFile = fileValidator.validate(file, requestedTitle);
        String objectKey = createObjectKey();
        String fileHash = uploadToStorage(file, validatedFile, objectKey);

        VideoEntity video = new VideoEntity();
        LocalDateTime now = LocalDateTime.now();
        video.setTitle(validatedFile.title());
        video.setOriginalFilename(validatedFile.originalFilename());
        video.setObjectKey(objectKey);
        video.setFileSize(validatedFile.size());
        video.setMimeType(validatedFile.contentType());
        video.setFileHash(fileHash);
        video.setStatus(UPLOADED_STATUS);
        video.setCreatedAt(now);
        video.setUpdatedAt(now);

        try {
            int insertedRows = videoRepository.insert(video);
            if (insertedRows != 1 || video.getId() == null) {
                throw new IllegalStateException("Video metadata insert did not return an id");
            }
        } catch (RuntimeException databaseException) {
            compensateStorage(objectKey, databaseException);
            throw new VideoAgentException(
                ErrorCode.VIDEO_UPLOAD_FAILED,
                "视频已上传，但元数据保存失败，已执行对象存储补偿",
                databaseException
            );
        }

        log.info("[videoId={}][stage=UPLOAD] video uploaded", video.getId());
        return new VideoUploadResponse(video.getId());
    }

    @Transactional(readOnly = true)
    public List<VideoResponse> listVideos() {
        return videoRepository.selectList(
                Wrappers.<VideoEntity>lambdaQuery()
                    .orderByDesc(VideoEntity::getCreatedAt)
                    .orderByDesc(VideoEntity::getId)
            )
            .stream()
            .map(VideoResponse::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public VideoResponse getVideo(long videoId) {
        VideoEntity video = videoRepository.selectById(videoId);
        if (video == null) {
            throw new VideoAgentException(ErrorCode.VIDEO_NOT_FOUND);
        }
        return VideoResponse.from(video);
    }

    private String uploadToStorage(
        MultipartFile file,
        ValidatedVideoFile validatedFile,
        String objectKey
    ) {
        MessageDigest digest = sha256();
        try (
            InputStream inputStream = file.getInputStream();
            DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest)
        ) {
            storageService.putObject(
                objectKey,
                digestInputStream,
                validatedFile.size(),
                validatedFile.contentType()
            );
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new VideoAgentException(ErrorCode.VIDEO_UPLOAD_FAILED, "无法读取上传的视频文件", exception);
        }
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new VideoAgentException(ErrorCode.INTERNAL_ERROR, "运行环境不支持 SHA-256", exception);
        }
    }

    private String createObjectKey() {
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        return "videos/%d/%02d/%02d/%s.mp4".formatted(
            date.getYear(),
            date.getMonthValue(),
            date.getDayOfMonth(),
            UUID.randomUUID()
        );
    }

    private void compensateStorage(String objectKey, RuntimeException databaseException) {
        try {
            storageService.removeObject(objectKey);
        } catch (RuntimeException compensationException) {
            databaseException.addSuppressed(compensationException);
            log.error("[stage=UPLOAD] failed to remove orphaned object key={}", objectKey, compensationException);
        }
    }
}
