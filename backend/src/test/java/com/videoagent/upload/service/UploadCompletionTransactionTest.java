package com.videoagent.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.storage.ObjectStorageService;
import com.videoagent.storage.StoredObject;
import com.videoagent.upload.dto.CompleteUploadResponse;
import com.videoagent.upload.entity.VideoUploadPartEntity;
import com.videoagent.upload.entity.VideoUploadSessionEntity;
import com.videoagent.upload.repository.VideoUploadPartRepository;
import com.videoagent.upload.repository.VideoUploadSessionRepository;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

class UploadCompletionTransactionTest {

    private final VideoUploadSessionRepository sessions = mock(VideoUploadSessionRepository.class);
    private final VideoUploadPartRepository parts = mock(VideoUploadPartRepository.class);
    private final VideoRepository videos = mock(VideoRepository.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final UploadTemporaryObjectCleaner cleaner = mock(UploadTemporaryObjectCleaner.class);
    private UploadCompletionTransaction transaction;

    @BeforeEach
    void setUp() {
        transaction = new UploadCompletionTransaction(sessions, parts, videos, storage, cleaner);
    }

    @Test
    void shouldComposeCreateExactlyOneVideoWithoutStartingAnalysis() {
        VideoUploadSessionEntity session = session("UPLOADING");
        when(sessions.lockById("u1")).thenReturn(session);
        when(parts.findByUploadId("u1")).thenReturn(List.of(part(1, 16, "e1"), part(2, 8, "e2")));
        when(storage.statObject("upload-parts/u1/part-00001"))
            .thenReturn(new StoredObject("p1", 16, "e1", "application/octet-stream"));
        when(storage.statObject("upload-parts/u1/part-00002"))
            .thenReturn(new StoredObject("p2", 8, "e2", "application/octet-stream"));
        when(storage.statObject("videos/final.mp4"))
            .thenReturn(new StoredObject("videos/final.mp4", 24, "final", "video/mp4"));
        when(storage.readObjectRange("videos/final.mp4", 0, 12)).thenReturn(mp4Header());
        when(storage.sha256Object("videos/final.mp4")).thenReturn(hash());
        when(videos.findByUserIdAndFileHash(7L, hash())).thenReturn(video(42L, "videos/final.mp4"));
        CompleteUploadResponse response = transaction.complete(7L, "u1");

        assertThat(response.videoId()).isEqualTo(42L);
        assertThat(response.reusedExistingVideo()).isFalse();
        verify(storage).composeObject(eq("videos/final.mp4"), any(), eq("video/mp4"));
        verify(storage).sha256Object("videos/final.mp4");
        verify(videos).insertOrReuseByUserAndFileHash(any(VideoEntity.class));
        assertThat(session.getStatus()).isEqualTo("COMPLETED");
        assertThat(session.getVideoId()).isEqualTo(42L);
        assertThat(session.getAnalysisTaskId()).isNull();
        verify(cleaner).cleanupAfterCommit(session);
    }

    @Test
    void shouldReturnExistingResultForRepeatedOrConcurrentComplete() {
        VideoUploadSessionEntity session = session("COMPLETED");
        session.setVideoId(42L);
        when(sessions.lockById("u1")).thenReturn(session);
        when(videos.selectById(42L)).thenReturn(video(42L, "videos/final.mp4"));

        CompleteUploadResponse first = transaction.complete(7L, "u1");
        CompleteUploadResponse second = transaction.complete(7L, "u1");

        assertThat(first).isEqualTo(second);
        verify(storage, never()).composeObject(any(), any(), any());
        verify(videos, never()).insertOrReuseByUserAndFileHash(any(VideoEntity.class));
    }

    @Test
    void shouldReuseCanonicalVideoWhenSameUserAlreadyOwnsHash() {
        VideoUploadSessionEntity session = session("UPLOADING");
        when(sessions.lockById("u1")).thenReturn(session);
        when(parts.findByUploadId("u1")).thenReturn(List.of(part(1, 16, "e1"), part(2, 8, "e2")));
        when(storage.statObject("upload-parts/u1/part-00001"))
            .thenReturn(new StoredObject("p1", 16, "e1", "application/octet-stream"));
        when(storage.statObject("upload-parts/u1/part-00002"))
            .thenReturn(new StoredObject("p2", 8, "e2", "application/octet-stream"));
        when(storage.statObject("videos/final.mp4"))
            .thenReturn(new StoredObject("videos/final.mp4", 24, "final", "video/mp4"));
        when(storage.readObjectRange("videos/final.mp4", 0, 12)).thenReturn(mp4Header());
        when(storage.sha256Object("videos/final.mp4")).thenReturn(hash());
        when(videos.findByUserIdAndFileHash(7L, hash())).thenReturn(video(41L, "videos/canonical.mp4"));

        CompleteUploadResponse response = transaction.complete(7L, "u1");

        assertThat(response.videoId()).isEqualTo(41L);
        assertThat(response.reusedExistingVideo()).isTrue();
        assertThat(session.getStatus()).isEqualTo("COMPLETED");
        assertThat(session.getVideoId()).isEqualTo(41L);
        verify(videos).insertOrReuseByUserAndFileHash(any(VideoEntity.class));
        verify(cleaner).cleanupAfterCommit(session);
    }

    @Test
    void shouldRejectMissingPartAndFinalSizeMismatch() {
        VideoUploadSessionEntity missing = session("UPLOADING");
        when(sessions.lockById("u1")).thenReturn(missing);
        when(parts.findByUploadId("u1")).thenReturn(List.of(part(1, 16, "e1")));
        assertThatThrownBy(() -> transaction.complete(7L, "u1"))
            .isInstanceOfSatisfying(VideoAgentException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UPLOAD_PART_INVALID));
        verify(storage, never()).composeObject(any(), any(), any());
    }

    @Test
    void shouldRejectExpiredSessionBeforeCompose() {
        VideoUploadSessionEntity expired = session("UPLOADING");
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(sessions.lockById("u1")).thenReturn(expired);

        assertThatThrownBy(() -> transaction.complete(7L, "u1"))
            .isInstanceOfSatisfying(VideoAgentException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UPLOAD_SESSION_EXPIRED));
        verify(storage, never()).composeObject(any(), any(), any());
    }

    private VideoUploadSessionEntity session(String status) {
        VideoUploadSessionEntity session = new VideoUploadSessionEntity();
        session.setId("u1");
        session.setUserId(7L);
        session.setFileName("lesson.mp4");
        session.setTitle("lesson");
        session.setFileSize(24L);
        session.setContentType("video/mp4");
        session.setChunkSize(16L);
        session.setTotalParts(2);
        session.setTempPrefix("upload-parts/u1");
        session.setObjectKey("videos/final.mp4");
        session.setStatus(status);
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        return session;
    }

    private VideoUploadPartEntity part(int number, long size, String etag) {
        VideoUploadPartEntity part = new VideoUploadPartEntity();
        part.setUploadId("u1");
        part.setPartNumber(number);
        part.setObjectKey("upload-parts/u1/part-%05d".formatted(number));
        part.setExpectedSize(size);
        part.setActualSize(size);
        part.setEtag(etag);
        part.setStatus("COMPLETED");
        return part;
    }

    private byte[] mp4Header() {
        return new byte[] {0, 0, 0, 0, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
    }

    private String hash() {
        return "a".repeat(64);
    }

    private VideoEntity video(long id, String objectKey) {
        VideoEntity video = new VideoEntity();
        video.setId(id);
        video.setObjectKey(objectKey);
        return video;
    }
}
