package com.videoagent.video.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.storage.ObjectStorageService;
import com.videoagent.video.dto.VideoPageResponse;
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
    private final VideoOwnershipService ownershipService;
    private final VideoDeletionService deletionService;

    public VideoService(
        VideoRepository videoRepository,
        VideoFileValidator fileValidator,
        ObjectStorageService storageService,
        VideoOwnershipService ownershipService,
        VideoDeletionService deletionService
    ) {
        this.videoRepository = videoRepository;
        this.fileValidator = fileValidator;
        this.storageService = storageService;
        this.ownershipService = ownershipService;
        this.deletionService = deletionService;
    }

    @Transactional
    public VideoUploadResponse upload(long userId, MultipartFile file, String requestedTitle) {
        ValidatedVideoFile validatedFile = fileValidator.validate(file, requestedTitle);
        String objectKey = createObjectKey();
        String fileHash = uploadToStorage(file, validatedFile, objectKey);

        VideoEntity video = new VideoEntity();
        LocalDateTime now = LocalDateTime.now();
        video.setUserId(userId);
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
    public VideoPageResponse listVideos(long userId, int page, int size, String keyword) {
        String normalizedKeyword = keyword == null ? "" : keyword.strip();
        Page<VideoEntity> result = videoRepository.selectPage(
            new Page<>(page, size),
            Wrappers.<VideoEntity>lambdaQuery()
                .eq(VideoEntity::getUserId, userId)
                .like(!normalizedKeyword.isEmpty(), VideoEntity::getTitle, normalizedKeyword)
                .orderByDesc(VideoEntity::getCreatedAt)
                .orderByDesc(VideoEntity::getId)
        );
        return new VideoPageResponse(
            result.getRecords().stream().map(VideoResponse::from).toList(),
            result.getCurrent(),
            result.getSize(),
            result.getTotal(),
            result.getPages()
        );
    }

    @Transactional(readOnly = true)
    public VideoResponse getVideo(long videoId, long userId) {
        return VideoResponse.from(ownershipService.requireOwned(videoId, userId));
    }

    @Transactional
    public VideoResponse updateTitle(long videoId, long userId, String requestedTitle) {
        String title = requestedTitle == null ? "" : requestedTitle.strip();
        if (title.isEmpty() || title.length() > 255) {
            throw new VideoAgentException(ErrorCode.VALIDATION_ERROR, "视频标题长度必须为 1 至 255 个字符");
        }
        ownershipService.requireOwned(videoId, userId);
        LocalDateTime now = LocalDateTime.now();
        int updated = videoRepository.update(
            null,
            Wrappers.<VideoEntity>lambdaUpdate()
                .eq(VideoEntity::getId, videoId)
                .eq(VideoEntity::getUserId, userId)
                .set(VideoEntity::getTitle, title)
                .set(VideoEntity::getUpdatedAt, now)
        );
        if (updated != 1) {
            throw new VideoAgentException(ErrorCode.VIDEO_NOT_FOUND);
        }
        return VideoResponse.from(ownershipService.requireOwned(videoId, userId));
    }

    public void deleteVideo(long videoId, long userId) {
        String objectKey = deletionService.deleteDatabaseRecords(videoId, userId);
        try {
            storageService.removeObject(objectKey);
        } catch (RuntimeException exception) {
            log.warn(
                "[videoId={}][stage=DELETE] database committed but object cleanup failed key={}",
                videoId,
                objectKey,
                exception
            );
        }
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
