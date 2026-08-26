package com.videoagent.upload.service;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.storage.ComposeObjectSource;
import com.videoagent.storage.ObjectStorageService;
import com.videoagent.storage.StoredObject;
import com.videoagent.upload.dto.CompleteUploadResponse;
import com.videoagent.upload.entity.UploadSessionStatus;
import com.videoagent.upload.entity.VideoUploadPartEntity;
import com.videoagent.upload.entity.VideoUploadSessionEntity;
import com.videoagent.upload.repository.VideoUploadPartRepository;
import com.videoagent.upload.repository.VideoUploadSessionRepository;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class UploadCompletionTransaction {

    private static final String UPLOADED_STATUS = "UPLOADED";

    private final VideoUploadSessionRepository sessionRepository;
    private final VideoUploadPartRepository partRepository;
    private final VideoRepository videoRepository;
    private final ObjectStorageService storageService;
    private final UploadTemporaryObjectCleaner temporaryObjectCleaner;

    public UploadCompletionTransaction(
        VideoUploadSessionRepository sessionRepository,
        VideoUploadPartRepository partRepository,
        VideoRepository videoRepository,
        ObjectStorageService storageService,
        UploadTemporaryObjectCleaner temporaryObjectCleaner
    ) {
        this.sessionRepository = sessionRepository;
        this.partRepository = partRepository;
        this.videoRepository = videoRepository;
        this.storageService = storageService;
        this.temporaryObjectCleaner = temporaryObjectCleaner;
    }

    @Transactional
    public CompleteUploadResponse complete(long userId, String uploadId) {
        VideoUploadSessionEntity session = sessionRepository.lockById(uploadId);
        UploadSessionService.requireOwnership(session, userId);
        if (UploadSessionStatus.COMPLETED.name().equals(session.getStatus())) {
            return completedResponse(session);
        }
        if (session.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new VideoAgentException(ErrorCode.UPLOAD_SESSION_EXPIRED);
        }
        if (!UploadSessionStatus.CREATED.name().equals(session.getStatus())
            && !UploadSessionStatus.UPLOADING.name().equals(session.getStatus())
            && !UploadSessionStatus.FAILED.name().equals(session.getStatus())) {
            throw new VideoAgentException(ErrorCode.UPLOAD_SESSION_STATE_CONFLICT);
        }

        LocalDateTime now = LocalDateTime.now();
        session.setStatus(UploadSessionStatus.COMPLETING.name());
        session.setLastError(null);
        session.setUpdatedAt(now);
        sessionRepository.updateById(session);

        List<ComposeObjectSource> sources = validateParts(session);
        storageService.composeObject(session.getObjectKey(), sources, session.getContentType());
        StoredObject completedObject = storageService.statObject(session.getObjectKey());
        if (completedObject.size() != session.getFileSize()) {
            throw new VideoAgentException(
                ErrorCode.UPLOAD_PART_INVALID,
                "合并后文件大小不匹配，期望 %d，实际 %d".formatted(session.getFileSize(), completedObject.size())
            );
        }
        validateMp4Signature(session.getObjectKey());
        String fileHash = null;
        if (session.getExpectedSha256() != null) {
            fileHash = storageService.sha256Object(session.getObjectKey());
            if (!session.getExpectedSha256().equalsIgnoreCase(fileHash)) {
                throw new VideoAgentException(ErrorCode.UPLOAD_PART_INVALID, "合并后文件 SHA-256 校验失败");
            }
        }

        VideoEntity video = new VideoEntity();
        video.setUserId(userId);
        video.setTitle(session.getTitle());
        video.setOriginalFilename(session.getFileName());
        video.setObjectKey(session.getObjectKey());
        video.setFileSize(session.getFileSize());
        video.setMimeType(session.getContentType());
        video.setFileHash(fileHash);
        video.setStatus(UPLOADED_STATUS);
        video.setCreatedAt(now);
        video.setUpdatedAt(now);
        if (videoRepository.insert(video) != 1 || video.getId() == null) {
            throw new VideoAgentException(ErrorCode.VIDEO_UPLOAD_FAILED, "视频记录创建失败");
        }

        session.setVideoId(video.getId());
        session.setStatus(UploadSessionStatus.COMPLETED.name());
        session.setCompletedAt(now);
        session.setUpdatedAt(now);
        sessionRepository.updateById(session);
        temporaryObjectCleaner.cleanupAfterCommit(session);
        return completedResponse(session);
    }

    private List<ComposeObjectSource> validateParts(VideoUploadSessionEntity session) {
        List<VideoUploadPartEntity> parts = partRepository.findByUploadId(session.getId());
        if (parts.size() != session.getTotalParts()) {
            throw new VideoAgentException(ErrorCode.UPLOAD_PART_INVALID, "仍有分片尚未上传完成");
        }
        Map<Integer, VideoUploadPartEntity> byNumber = new HashMap<>();
        for (VideoUploadPartEntity part : parts) {
            byNumber.put(part.getPartNumber(), part);
        }
        List<ComposeObjectSource> sources = new ArrayList<>(session.getTotalParts());
        for (int partNumber = 1; partNumber <= session.getTotalParts(); partNumber++) {
            VideoUploadPartEntity part = byNumber.get(partNumber);
            if (part == null || !"COMPLETED".equals(part.getStatus())) {
                throw new VideoAgentException(ErrorCode.UPLOAD_PART_INVALID, "缺少分片 " + partNumber);
            }
            long expectedSize = UploadSessionService.expectedPartSize(session, partNumber);
            StoredObject stored = storageService.statObject(part.getObjectKey());
            if (part.getExpectedSize() != expectedSize
                || part.getActualSize() != expectedSize
                || stored.size() != expectedSize
                || !stored.etag().equals(part.getEtag())) {
                throw new VideoAgentException(ErrorCode.UPLOAD_PART_INVALID, "分片 %d 在确认后发生变化".formatted(partNumber));
            }
            sources.add(new ComposeObjectSource(part.getObjectKey(), part.getEtag()));
        }
        return sources;
    }

    private void validateMp4Signature(String objectKey) {
        byte[] header = storageService.readObjectRange(objectKey, 0, 12);
        boolean valid = header.length == 12
            && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p';
        if (!valid) {
            throw new VideoAgentException(ErrorCode.VIDEO_FORMAT_NOT_SUPPORTED, "合并文件不是有效的 MP4 文件");
        }
    }

    private CompleteUploadResponse completedResponse(VideoUploadSessionEntity session) {
        if (session.getVideoId() == null) {
            throw new VideoAgentException(ErrorCode.INTERNAL_ERROR, "已完成上传缺少视频标识");
        }
        return new CompleteUploadResponse(
            session.getId(), session.getVideoId(), session.getStatus()
        );
    }
}
