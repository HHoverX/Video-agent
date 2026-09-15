package com.videoagent.upload.service;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.upload.dto.CompleteUploadRequest;
import com.videoagent.upload.dto.CompleteUploadResponse;
import com.videoagent.upload.entity.UploadSessionStatus;
import com.videoagent.upload.entity.VideoUploadSessionEntity;
import com.videoagent.upload.repository.VideoUploadSessionRepository;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class UploadCompletionTransaction {

    private static final String UPLOADED_STATUS = "UPLOADED";

    private final VideoUploadSessionRepository sessionRepository;
    private final VideoRepository videoRepository;
    private final UploadTemporaryObjectCleaner temporaryObjectCleaner;

    public UploadCompletionTransaction(
        VideoUploadSessionRepository sessionRepository,
        VideoRepository videoRepository,
        UploadTemporaryObjectCleaner temporaryObjectCleaner
    ) {
        this.sessionRepository = sessionRepository;
        this.videoRepository = videoRepository;
        this.temporaryObjectCleaner = temporaryObjectCleaner;
    }

    @Transactional
    public BeginCompletionResult beginCompletion(long userId, String uploadId, CompleteUploadRequest request) {
        VideoUploadSessionEntity session = sessionRepository.lockById(uploadId);
        UploadSessionService.requireOwnership(session, userId);
        if (UploadSessionStatus.COMPLETED.name().equals(session.getStatus())) {
            return BeginCompletionResult.completed(completedResponse(session));
        }
        if (UploadSessionStatus.COMPLETING.name().equals(session.getStatus())) {
            throw new VideoAgentException(ErrorCode.UPLOAD_SESSION_STATE_CONFLICT, "上传正在完成中");
        }
        if (session.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new VideoAgentException(ErrorCode.UPLOAD_SESSION_EXPIRED);
        }
        if (!UploadSessionStatus.CREATED.name().equals(session.getStatus())
            && !UploadSessionStatus.UPLOADING.name().equals(session.getStatus())
            && !UploadSessionStatus.FAILED.name().equals(session.getStatus())) {
            throw new VideoAgentException(ErrorCode.UPLOAD_SESSION_STATE_CONFLICT);
        }

        String suppliedSha256 = UploadSessionService.normalizeSha256(request == null ? null : request.sha256());
        // This is the client declaration captured when the session was created.
        // It is a deduplication key, not a server-verified digest of the composed object.
        String fileHash = UploadSessionService.normalizeSha256(session.getExpectedSha256());
        if (fileHash == null) {
            if (suppliedSha256 == null) {
                throw new VideoAgentException(ErrorCode.INVALID_REQUEST, "历史上传会话必须补交 SHA-256");
            }
            fileHash = suppliedSha256;
            session.setExpectedSha256(fileHash);
        } else if (suppliedSha256 != null && !fileHash.equals(suppliedSha256)) {
            throw new VideoAgentException(ErrorCode.UPLOAD_PART_INVALID, "补交的 SHA-256 与上传会话不一致");
        }

        LocalDateTime now = LocalDateTime.now();
        String completionToken = UUID.randomUUID().toString();
        String previousStatus = session.getStatus();
        session.setStatus(UploadSessionStatus.COMPLETING.name());
        session.setCompletingAt(now);
        session.setCompletionToken(completionToken);
        session.setLastError(null);
        session.setUpdatedAt(now);
        if (sessionRepository.markCompletionStarted(
            uploadId, previousStatus, completionToken, now, fileHash
        ) != 1) {
            throw new VideoAgentException(ErrorCode.VIDEO_UPLOAD_FAILED, "无法开始完成上传");
        }

        return BeginCompletionResult.started(new UploadCompletionAttempt(
            uploadId,
            userId,
            session.getObjectKey(),
            session.getTempPrefix(),
            session.getContentType(),
            session.getFileSize(),
            session.getChunkSize(),
            session.getTotalParts(),
            fileHash,
            completionToken,
            now,
            session.getExpiresAt()
        ));
    }

    @Transactional
    public CompleteUploadResponse finalizeCompletion(UploadCompletionAttempt attempt) {
        VideoUploadSessionEntity session = sessionRepository.lockById(attempt.uploadId());
        UploadSessionService.requireOwnership(session, attempt.userId());
        if (UploadSessionStatus.COMPLETED.name().equals(session.getStatus())) {
            return completedResponse(session);
        }
        if (!UploadSessionStatus.COMPLETING.name().equals(session.getStatus())
            || !attempt.completionToken().equals(session.getCompletionToken())) {
            throw new VideoAgentException(ErrorCode.UPLOAD_SESSION_STATE_CONFLICT, "上传完成请求已失去执行资格");
        }

        String fileHash = UploadSessionService.normalizeSha256(session.getExpectedSha256());
        if (fileHash == null) {
            throw new VideoAgentException(ErrorCode.VIDEO_UPLOAD_FAILED, "上传会话缺少 SHA-256");
        }

        LocalDateTime now = LocalDateTime.now();
        VideoEntity video = new VideoEntity();
        video.setUserId(attempt.userId());
        video.setTitle(session.getTitle());
        video.setOriginalFilename(session.getFileName());
        video.setObjectKey(session.getObjectKey());
        video.setFileSize(session.getFileSize());
        video.setMimeType(session.getContentType());
        video.setFileHash(fileHash);
        video.setStatus(UPLOADED_STATUS);
        video.setCreatedAt(now);
        video.setUpdatedAt(now);
        videoRepository.insertOrReuseByUserAndFileHash(video);
        VideoEntity canonicalVideo = videoRepository.findByUserIdAndFileHash(attempt.userId(), fileHash);
        if (canonicalVideo == null || canonicalVideo.getId() == null) {
            throw new VideoAgentException(ErrorCode.VIDEO_UPLOAD_FAILED, "视频记录创建或复用失败");
        }
        if (sessionRepository.markCompletionCompleted(
            session.getId(), attempt.completionToken(), canonicalVideo.getId(), now
        ) != 1) {
            throw new VideoAgentException(ErrorCode.UPLOAD_SESSION_STATE_CONFLICT, "上传完成请求已失去执行资格");
        }

        session.setVideoId(canonicalVideo.getId());
        session.setStatus(UploadSessionStatus.COMPLETED.name());
        session.setCompletingAt(null);
        session.setCompletionToken(null);
        session.setCompletedAt(now);
        session.setUpdatedAt(now);
        temporaryObjectCleaner.cleanupAfterCommit(session);
        return completedResponse(session, canonicalVideo);
    }

    private CompleteUploadResponse completedResponse(VideoUploadSessionEntity session) {
        if (session.getVideoId() == null) {
            throw new VideoAgentException(ErrorCode.INTERNAL_ERROR, "已完成上传缺少视频标识");
        }
        VideoEntity canonicalVideo = videoRepository.selectById(session.getVideoId());
        if (canonicalVideo == null) {
            throw new VideoAgentException(ErrorCode.INTERNAL_ERROR, "已完成上传缺少正式视频记录");
        }
        return completedResponse(session, canonicalVideo);
    }

    private CompleteUploadResponse completedResponse(
        VideoUploadSessionEntity session,
        VideoEntity canonicalVideo
    ) {
        return new CompleteUploadResponse(
            session.getId(), session.getVideoId(), session.getStatus(),
            !session.getObjectKey().equals(canonicalVideo.getObjectKey())
        );
    }
}

record BeginCompletionResult(CompleteUploadResponse completedResponse, UploadCompletionAttempt attempt) {

    static BeginCompletionResult completed(CompleteUploadResponse response) {
        return new BeginCompletionResult(response, null);
    }

    static BeginCompletionResult started(UploadCompletionAttempt attempt) {
        return new BeginCompletionResult(null, attempt);
    }
}

record UploadCompletionAttempt(
    String uploadId,
    long userId,
    String objectKey,
    String tempPrefix,
    String contentType,
    long fileSize,
    long chunkSize,
    int totalParts,
    String expectedSha256,
    String completionToken,
    LocalDateTime completingAt,
    LocalDateTime expiresAt
) {
}
