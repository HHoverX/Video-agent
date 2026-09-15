package com.videoagent.upload.service;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.storage.ObjectStorageService;
import com.videoagent.storage.StoredObject;
import com.videoagent.upload.dto.CompleteUploadPartRequest;
import com.videoagent.upload.dto.CreateUploadSessionRequest;
import com.videoagent.upload.dto.UploadPartResponse;
import com.videoagent.upload.dto.UploadPartUrlResponse;
import com.videoagent.upload.dto.UploadSessionResponse;
import com.videoagent.upload.entity.UploadSessionStatus;
import com.videoagent.upload.entity.VideoUploadSessionEntity;
import com.videoagent.upload.repository.VideoUploadSessionRepository;
import com.videoagent.video.service.VideoUploadProperties;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class UploadSessionService {

    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of("video/mp4", "application/mp4");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final VideoUploadSessionRepository sessionRepository;
    private final ObjectStorageService storageService;
    private final UploadPartStateService partStateService;
    private final VideoUploadProperties properties;
    private final UploadTemporaryObjectCleaner temporaryObjectCleaner;
    private final VideoRepository videoRepository;

    public UploadSessionService(
        VideoUploadSessionRepository sessionRepository,
        ObjectStorageService storageService,
        UploadPartStateService partStateService,
        VideoUploadProperties properties,
        UploadTemporaryObjectCleaner temporaryObjectCleaner,
        VideoRepository videoRepository
    ) {
        this.sessionRepository = sessionRepository;
        this.storageService = storageService;
        this.partStateService = partStateService;
        this.properties = properties;
        this.temporaryObjectCleaner = temporaryObjectCleaner;
        this.videoRepository = videoRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UploadSessionResponse create(long userId, CreateUploadSessionRequest request) {
        String fileName = safeFileName(request.fileName());
        String contentType = request.contentType().strip().toLowerCase(Locale.ROOT);
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".mp4")
            || !SUPPORTED_CONTENT_TYPES.contains(contentType)) {
            throw new VideoAgentException(ErrorCode.VIDEO_FORMAT_NOT_SUPPORTED);
        }
        long fileSize = request.fileSize();
        if (fileSize > properties.maxFileSize().toBytes()) {
            throw new VideoAgentException(
                ErrorCode.VIDEO_FILE_TOO_LARGE,
                "视频文件不能超过 " + properties.maxFileSize().toGigabytes() + " GB"
            );
        }
        long chunkSize = request.chunkSize() == null
            ? properties.defaultChunkSize().toBytes()
            : request.chunkSize();
        if (chunkSize < properties.minChunkSize().toBytes()
            || chunkSize > properties.maxChunkSize().toBytes()) {
            throw new VideoAgentException(ErrorCode.INVALID_REQUEST, "分片大小超出允许范围");
        }
        long totalPartsLong = (fileSize + chunkSize - 1) / chunkSize;
        if (totalPartsLong < 1 || totalPartsLong > properties.maxParts()) {
            throw new VideoAgentException(ErrorCode.INVALID_REQUEST, "视频分片数量超出允许范围");
        }
        String title = request.title().strip();
        if (title.isBlank() || title.length() > 255) {
            throw new VideoAgentException(ErrorCode.INVALID_REQUEST, "视频标题长度必须为 1 至 255 个字符");
        }
        String sha256 = requireSha256(request.sha256());

        VideoEntity existingVideo = videoRepository.findByUserIdAndFileHash(userId, sha256);
        if (existingVideo != null && existingVideo.getId() != null) {
            return deduplicatedResponse(
                fileName, title, fileSize, contentType, chunkSize,
                Math.toIntExact(totalPartsLong), existingVideo.getId()
            );
        }

        LocalDateTime now = LocalDateTime.now();
        sessionRepository.expireReusableByHash(userId, sha256, now);
        VideoUploadSessionEntity reusable = sessionRepository.findReusableByHash(userId, sha256, now);
        if (reusable != null) {
            return response(reusable, partStateService.completedPartNumbers(reusable));
        }

        String uploadId = UUID.randomUUID().toString();
        VideoUploadSessionEntity session = new VideoUploadSessionEntity();
        session.setId(uploadId);
        session.setUserId(userId);
        session.setFileName(fileName);
        session.setTitle(title);
        session.setFileSize(fileSize);
        session.setContentType(contentType);
        session.setChunkSize(chunkSize);
        session.setTotalParts(Math.toIntExact(totalPartsLong));
        session.setObjectKey(UploadKeyPolicy.finalObjectKey());
        session.setTempPrefix(UploadKeyPolicy.tempPrefix(uploadId));
        session.setExpectedSha256(sha256);
        session.setStatus(UploadSessionStatus.CREATED.name());
        session.setExpiresAt(now.plus(properties.sessionTtl()));
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        try {
            if (sessionRepository.insert(session) != 1) {
                throw new VideoAgentException(ErrorCode.VIDEO_UPLOAD_FAILED, "无法创建上传会话");
            }
        } catch (DuplicateKeyException exception) {
            VideoUploadSessionEntity winner = sessionRepository.findReusableByHash(
                userId, sha256, LocalDateTime.now()
            );
            if (winner != null) {
                return response(winner, partStateService.completedPartNumbers(winner));
            }
            VideoEntity completedVideo = videoRepository.findByUserIdAndFileHash(userId, sha256);
            if (completedVideo != null && completedVideo.getId() != null) {
                return deduplicatedResponse(
                    fileName, title, fileSize, contentType, chunkSize,
                    Math.toIntExact(totalPartsLong), completedVideo.getId()
                );
            }
            throw new VideoAgentException(
                ErrorCode.VIDEO_UPLOAD_FAILED,
                "并发创建上传会话后无法读取获胜会话，请重试",
                exception
            );
        }
        return response(session, List.of());
    }

    public UploadSessionResponse get(long userId, String uploadId) {
        VideoUploadSessionEntity session = requireOwned(uploadId, userId);
        return response(session, partStateService.completedPartNumbers(session));
    }

    public UploadPartUrlResponse createPartUrl(long userId, String uploadId, int partNumber) {
        VideoUploadSessionEntity session = requireOwned(uploadId, userId);
        requireResumable(session);
        validatePartNumber(session, partNumber);
        long expectedSize = expectedPartSize(session, partNumber);
        String objectKey = UploadKeyPolicy.partObjectKey(session.getTempPrefix(), partNumber);
        String url = storageService.presignPutObject(objectKey, properties.presignTtl());
        if (UploadSessionStatus.CREATED.name().equals(session.getStatus())
            || UploadSessionStatus.FAILED.name().equals(session.getStatus())) {
            sessionRepository.markUploading(uploadId, LocalDateTime.now());
        }
        Instant expiresAt = Instant.now().plus(properties.presignTtl());
        return new UploadPartUrlResponse(partNumber, expectedSize, false, url, expiresAt);
    }

    public UploadPartResponse confirmPart(
        long userId,
        String uploadId,
        int partNumber,
        CompleteUploadPartRequest request
    ) {
        VideoUploadSessionEntity session = requireOwned(uploadId, userId);
        requireResumable(session);
        validatePartNumber(session, partNumber);
        String objectKey = UploadKeyPolicy.partObjectKey(session.getTempPrefix(), partNumber);
        long expectedSize = expectedPartSize(session, partNumber);
        StoredObject stored = storageService.statObject(objectKey);
        if (stored.size() != expectedSize) {
            throw new VideoAgentException(
                ErrorCode.UPLOAD_PART_INVALID,
                "分片 %d 大小不匹配，期望 %d，实际 %d".formatted(partNumber, expectedSize, stored.size())
            );
        }
        String checksum = normalizeSha256(request == null ? null : request.sha256());
        try {
            partStateService.markCompleted(session, partNumber);
        } catch (DataAccessException exception) {
            throw new VideoAgentException(
                ErrorCode.VIDEO_UPLOAD_FAILED,
                "分片已上传，但状态暂时无法确认，请重试",
                exception
            );
        }
        return new UploadPartResponse(partNumber, stored.size(), stored.etag(), checksum);
    }

    @Transactional
    public void cancel(long userId, String uploadId) {
        VideoUploadSessionEntity session = sessionRepository.lockById(uploadId);
        requireOwnership(session, userId);
        if (UploadSessionStatus.COMPLETED.name().equals(session.getStatus())
            || UploadSessionStatus.COMPLETING.name().equals(session.getStatus())) {
            throw new VideoAgentException(ErrorCode.UPLOAD_SESSION_STATE_CONFLICT, "完成中或已完成的上传不能取消");
        }
        if (UploadSessionStatus.CANCELLED.name().equals(session.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        session.setStatus(UploadSessionStatus.CANCELLED.name());
        session.setCancelledAt(now);
        session.setUpdatedAt(now);
        sessionRepository.updateById(session);
        temporaryObjectCleaner.cleanupAfterCommit(session);
    }

    VideoUploadSessionEntity requireOwned(String uploadId, long userId) {
        VideoUploadSessionEntity session = sessionRepository.findOwned(uploadId, userId);
        if (session == null) {
            throw new VideoAgentException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
        return session;
    }

    static void requireOwnership(VideoUploadSessionEntity session, long userId) {
        if (session == null || session.getUserId() == null || session.getUserId() != userId) {
            throw new VideoAgentException(ErrorCode.UPLOAD_SESSION_NOT_FOUND);
        }
    }

    static long expectedPartSize(VideoUploadSessionEntity session, int partNumber) {
        int firstPartNumber = UploadKeyPolicy.firstPartNumber(session.getTempPrefix());
        long offset = (long) (partNumber - firstPartNumber) * session.getChunkSize();
        return Math.min(session.getChunkSize(), session.getFileSize() - offset);
    }

    private void requireResumable(VideoUploadSessionEntity session) {
        if (session.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new VideoAgentException(ErrorCode.UPLOAD_SESSION_EXPIRED);
        }
        if (!UploadSessionStatus.CREATED.name().equals(session.getStatus())
            && !UploadSessionStatus.UPLOADING.name().equals(session.getStatus())
            && !UploadSessionStatus.FAILED.name().equals(session.getStatus())) {
            throw new VideoAgentException(ErrorCode.UPLOAD_SESSION_STATE_CONFLICT);
        }
    }

    private void validatePartNumber(VideoUploadSessionEntity session, int partNumber) {
        int firstPartNumber = UploadKeyPolicy.firstPartNumber(session.getTempPrefix());
        if (partNumber < firstPartNumber || partNumber >= firstPartNumber + session.getTotalParts()) {
            throw new VideoAgentException(ErrorCode.INVALID_REQUEST, "partNumber 超出上传会话范围");
        }
    }

    private UploadSessionResponse response(
        VideoUploadSessionEntity session,
        List<Integer> completedPartNumbers
    ) {
        List<UploadPartResponse> completed = completedPartNumbers.stream()
            .map(partNumber -> new UploadPartResponse(
                partNumber, expectedPartSize(session, partNumber), null, null
            ))
            .toList();
        long uploadedBytes = completed.stream().mapToLong(UploadPartResponse::size).sum();
        return new UploadSessionResponse(
            session.getId(), false, session.getFileName(), session.getTitle(), session.getFileSize(),
            session.getContentType(), session.getChunkSize(), session.getTotalParts(),
            UploadKeyPolicy.firstPartNumber(session.getTempPrefix()), session.getStatus(),
            session.getExpiresAt(), uploadedBytes, completed, properties.maxClientConcurrency(),
            session.getVideoId(), session.getAnalysisTaskId(), session.getLastError()
        );
    }

    private String safeFileName(String value) {
        try {
            Path path = Path.of(value).getFileName();
            String name = path == null ? "" : path.toString();
            if (name.isBlank() || name.length() > 255) {
                throw new VideoAgentException(ErrorCode.INVALID_REQUEST, "视频文件名长度必须为 1 至 255 个字符");
            }
            return name;
        } catch (InvalidPathException exception) {
            throw new VideoAgentException(ErrorCode.INVALID_REQUEST, "视频文件名不合法", exception);
        }
    }

    static String normalizeSha256(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!SHA256.matcher(value).matches()) {
            throw new VideoAgentException(ErrorCode.INVALID_REQUEST, "SHA-256 必须是 64 位十六进制字符串");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private UploadSessionResponse deduplicatedResponse(
        String fileName,
        String title,
        long fileSize,
        String contentType,
        long chunkSize,
        int totalParts,
        long videoId
    ) {
        return new UploadSessionResponse(
            null, true, fileName, title, fileSize, contentType, chunkSize,
            totalParts, 0, UploadSessionStatus.COMPLETED.name(), null,
            fileSize, List.of(), properties.maxClientConcurrency(), videoId, null, null
        );
    }

    private static String requireSha256(String value) {
        String normalized = normalizeSha256(value);
        if (normalized == null) {
            throw new VideoAgentException(ErrorCode.INVALID_REQUEST, "创建上传会话必须提供 SHA-256");
        }
        return normalized;
    }
}
