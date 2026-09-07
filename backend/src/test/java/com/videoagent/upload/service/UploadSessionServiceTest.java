package com.videoagent.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.storage.ObjectStorageService;
import com.videoagent.storage.StoredObject;
import com.videoagent.upload.dto.CompleteUploadPartRequest;
import com.videoagent.upload.dto.CreateUploadSessionRequest;
import com.videoagent.upload.dto.UploadSessionResponse;
import com.videoagent.upload.entity.VideoUploadSessionEntity;
import com.videoagent.upload.repository.VideoUploadSessionRepository;
import com.videoagent.video.service.VideoUploadProperties;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

class UploadSessionServiceTest {

    private final VideoUploadSessionRepository sessions = mock(VideoUploadSessionRepository.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final UploadPartStateService partState = mock(UploadPartStateService.class);
    private final UploadTemporaryObjectCleaner cleaner = mock(UploadTemporaryObjectCleaner.class);
    private final VideoRepository videos = mock(VideoRepository.class);
    private UploadSessionService service;

    @BeforeEach
    void setUp() {
        VideoUploadProperties properties = new VideoUploadProperties(
            DataSize.ofGigabytes(20), DataSize.ofMegabytes(16), DataSize.ofMegabytes(5),
            DataSize.ofMegabytes(128), 10_000, Duration.ofHours(24), Duration.ofMinutes(15), 3,
            Duration.ofMinutes(30)
        );
        service = new UploadSessionService(sessions, storage, partState, properties, cleaner, videos);
    }

    @Test
    void shouldCreateServerOwnedSessionAndBoundPartCount() {
        when(sessions.insert(any(VideoUploadSessionEntity.class))).thenReturn(1);
        CreateUploadSessionRequest request = new CreateUploadSessionRequest(
            "lesson.mp4", "lesson", 40L * 1024 * 1024, "video/mp4", 16L * 1024 * 1024, hash()
        );

        UploadSessionResponse response = service.create(7L, request);

        assertThat(response.totalParts()).isEqualTo(3);
        assertThat(response.maxConcurrency()).isEqualTo(3);
        var captor = org.mockito.ArgumentCaptor.forClass(VideoUploadSessionEntity.class);
        verify(sessions).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getObjectKey()).startsWith("videos/").endsWith(".mp4");
        assertThat(captor.getValue().getTempPrefix()).isEqualTo("uploads/" + response.uploadId() + "/parts");
        assertThat(response.partNumberBase()).isZero();
        assertThat(captor.getValue().getExpectedSha256()).isEqualTo(hash());
    }

    @Test
    void shouldReturnOwnedExistingVideoWithoutCreatingUploadSession() {
        VideoEntity existing = new VideoEntity();
        existing.setId(42L);
        when(videos.findByUserIdAndFileHash(7L, hash())).thenReturn(existing);
        CreateUploadSessionRequest request = new CreateUploadSessionRequest(
            "lesson.mp4", "lesson", 40L * 1024 * 1024, "video/mp4", 16L * 1024 * 1024, hash()
        );

        UploadSessionResponse response = service.create(7L, request);

        assertThat(response.deduplicated()).isTrue();
        assertThat(response.videoId()).isEqualTo(42L);
        assertThat(response.uploadId()).isNull();
        verify(sessions, never()).insert(any(VideoUploadSessionEntity.class));
    }

    @Test
    void shouldScopeHashLookupToCurrentUser() {
        VideoEntity otherUsersVideo = new VideoEntity();
        otherUsersVideo.setId(42L);
        when(videos.findByUserIdAndFileHash(7L, hash())).thenReturn(otherUsersVideo);
        when(sessions.insert(any(VideoUploadSessionEntity.class))).thenReturn(1);
        CreateUploadSessionRequest request = new CreateUploadSessionRequest(
            "lesson.mp4", "lesson", 40L * 1024 * 1024, "video/mp4", 16L * 1024 * 1024, hash()
        );

        UploadSessionResponse response = service.create(8L, request);

        assertThat(response.deduplicated()).isFalse();
        assertThat(response.uploadId()).isNotNull();
        verify(videos).findByUserIdAndFileHash(8L, hash());
        verify(sessions).insert(any(VideoUploadSessionEntity.class));
    }

    @Test
    void shouldRequireSha256WhenCreatingSession() {
        CreateUploadSessionRequest missing = new CreateUploadSessionRequest(
            "lesson.mp4", "lesson", 40L * 1024 * 1024, "video/mp4", 16L * 1024 * 1024, null
        );
        CreateUploadSessionRequest invalid = new CreateUploadSessionRequest(
            "lesson.mp4", "lesson", 40L * 1024 * 1024, "video/mp4", 16L * 1024 * 1024, "not-a-hash"
        );

        assertThatThrownBy(() -> service.create(7L, missing))
            .isInstanceOfSatisfying(VideoAgentException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> service.create(7L, invalid))
            .isInstanceOfSatisfying(VideoAgentException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        verify(sessions, never()).insert(any(VideoUploadSessionEntity.class));
    }

    @Test
    void shouldResumeWithOnlyPersistedCompletedParts() {
        VideoUploadSessionEntity session = session("UPLOADING");
        when(sessions.findOwned("u1", 7L)).thenReturn(session);
        when(partState.completedPartNumbers(session)).thenReturn(List.of(1, 3));

        UploadSessionResponse response = service.get(7L, "u1");

        assertThat(response.completedParts()).extracting(p -> p.partNumber()).containsExactly(1, 3);
        assertThat(response.uploadedBytes()).isEqualTo(24L);
    }

    @Test
    void shouldConfirmDuplicatePartIdempotentlyWhenEtagMatches() {
        VideoUploadSessionEntity session = session("UPLOADING");
        session.setFileSize(24L);
        session.setChunkSize(16L);
        session.setTotalParts(2);
        when(sessions.findOwned("u1", 7L)).thenReturn(session);
        when(storage.statObject("upload-parts/u1/part-00001"))
            .thenReturn(new StoredObject("upload-parts/u1/part-00001", 16, "etag-1", "application/octet-stream"));
        when(partState.markCompleted(session, 1)).thenReturn(false, true);

        service.confirmPart(7L, "u1", 1, new CompleteUploadPartRequest(null));
        service.confirmPart(7L, "u1", 1, new CompleteUploadPartRequest(null));

        verify(partState, org.mockito.Mockito.times(2)).markCompleted(session, 1);
        verify(sessions, never()).updateById(any(VideoUploadSessionEntity.class));
    }

    @Test
    void shouldConfirmZeroBasedFirstAndShortLastPartWithExactKeysAndSizes() {
        VideoUploadSessionEntity session = session("UPLOADING");
        session.setTempPrefix("uploads/u1/parts");
        session.setFileSize(40L);
        session.setChunkSize(16L);
        session.setTotalParts(3);
        when(sessions.findOwned("u1", 7L)).thenReturn(session);
        when(storage.statObject("uploads/u1/parts/0"))
            .thenReturn(new StoredObject("uploads/u1/parts/0", 16L, "first", "video/mp4"));
        when(storage.statObject("uploads/u1/parts/2"))
            .thenReturn(new StoredObject("uploads/u1/parts/2", 8L, "last", "video/mp4"));

        assertThat(service.confirmPart(7L, "u1", 0, null).size()).isEqualTo(16L);
        assertThat(service.confirmPart(7L, "u1", 2, null).size()).isEqualTo(8L);

        verify(partState).markCompleted(session, 0);
        verify(partState).markCompleted(session, 2);
    }

    @Test
    void shouldRejectZeroBasedPartOutsideSessionRangeAndCompletionStates() {
        VideoUploadSessionEntity session = session("UPLOADING");
        session.setTempPrefix("uploads/u1/parts");
        when(sessions.findOwned("u1", 7L)).thenReturn(session);

        assertThatThrownBy(() -> service.createPartUrl(7L, "u1", -1))
            .isInstanceOfSatisfying(VideoAgentException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> service.confirmPart(7L, "u1", 3, null))
            .isInstanceOfSatisfying(VideoAgentException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));

        session.setStatus("COMPLETING");
        assertThatThrownBy(() -> service.createPartUrl(7L, "u1", 0))
            .isInstanceOfSatisfying(VideoAgentException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UPLOAD_SESSION_STATE_CONFLICT));
        assertThatThrownBy(() -> service.confirmPart(7L, "u1", 0, null))
            .isInstanceOfSatisfying(VideoAgentException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UPLOAD_SESSION_STATE_CONFLICT));
    }

    @Test
    void shouldRejectMissingOrWrongSizedZeroBasedMinioPartWithoutBitmapWrite() {
        VideoUploadSessionEntity session = session("UPLOADING");
        session.setTempPrefix("uploads/u1/parts");
        when(sessions.findOwned("u1", 7L)).thenReturn(session);
        when(storage.statObject("uploads/u1/parts/0"))
            .thenThrow(new VideoAgentException(ErrorCode.STORAGE_ERROR, "missing"));

        assertThatThrownBy(() -> service.confirmPart(7L, "u1", 0, null))
            .isInstanceOf(VideoAgentException.class)
            .hasMessage("missing");

        org.mockito.Mockito.doReturn(new StoredObject("uploads/u1/parts/0", 15L, "bad", "video/mp4"))
            .when(storage).statObject("uploads/u1/parts/0");
        assertThatThrownBy(() -> service.confirmPart(7L, "u1", 0, null))
            .isInstanceOfSatisfying(VideoAgentException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UPLOAD_PART_INVALID));
        verify(partState, never()).markCompleted(any(), anyInt());
    }

    @Test
    void shouldMarkCreatedSessionUploadingWhenCreatingPartUrl() {
        VideoUploadSessionEntity session = session("CREATED");
        when(sessions.findOwned("u1", 7L)).thenReturn(session);
        when(storage.presignPutObject(eq("upload-parts/u1/part-00001"), any(Duration.class)))
            .thenReturn("https://minio.example/upload");

        service.createPartUrl(7L, "u1", 1);

        verify(sessions).markUploading(eq("u1"), any(LocalDateTime.class));
        verify(sessions, never()).updateById(any(VideoUploadSessionEntity.class));
    }

    @Test
    void shouldMarkFailedSessionUploadingWhenCreatingPartUrl() {
        VideoUploadSessionEntity session = session("FAILED");
        when(sessions.findOwned("u1", 7L)).thenReturn(session);
        when(storage.presignPutObject(eq("upload-parts/u1/part-00001"), any(Duration.class)))
            .thenReturn("https://minio.example/upload");

        service.createPartUrl(7L, "u1", 1);

        verify(sessions).markUploading(eq("u1"), any(LocalDateTime.class));
        verify(sessions, never()).updateById(any(VideoUploadSessionEntity.class));
    }

    @Test
    void shouldReturnRetryableErrorWhenBitmapWriteFailsWithoutDeletingMinioPart() {
        VideoUploadSessionEntity session = session("UPLOADING");
        when(sessions.findOwned("u1", 7L)).thenReturn(session);
        when(storage.statObject("upload-parts/u1/part-00001"))
            .thenReturn(new StoredObject("upload-parts/u1/part-00001", 16, "etag-2", "application/octet-stream"));
        when(partState.markCompleted(session, 1))
            .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> service.confirmPart(7L, "u1", 1, null))
            .isInstanceOfSatisfying(VideoAgentException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.VIDEO_UPLOAD_FAILED));

        verify(storage, never()).removeObject(any());
        verify(sessions, never()).updateById(any(VideoUploadSessionEntity.class));
    }

    @Test
    void shouldRejectWrongOwnerWrongPartSizeAndExpiredSession() {
        when(sessions.findOwned("u1", 8L)).thenReturn(null);
        assertThatThrownBy(() -> service.get(8L, "u1"))
            .isInstanceOfSatisfying(VideoAgentException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UPLOAD_SESSION_NOT_FOUND));

        VideoUploadSessionEntity session = session("UPLOADING");
        when(sessions.findOwned("u1", 7L)).thenReturn(session);
        when(storage.statObject("upload-parts/u1/part-00001"))
            .thenReturn(new StoredObject("upload-parts/u1/part-00001", 15, "bad", "application/octet-stream"));
        assertThatThrownBy(() -> service.confirmPart(7L, "u1", 1, null))
            .isInstanceOfSatisfying(VideoAgentException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UPLOAD_PART_INVALID));

        session.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        assertThatThrownBy(() -> service.createPartUrl(7L, "u1", 1))
            .isInstanceOfSatisfying(VideoAgentException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UPLOAD_SESSION_EXPIRED));
    }

    @Test
    void shouldRejectCancellationWhileCompletionOwnsTheSession() {
        VideoUploadSessionEntity session = session("COMPLETING");
        when(sessions.lockById("u1")).thenReturn(session);

        assertThatThrownBy(() -> service.cancel(7L, "u1"))
            .isInstanceOfSatisfying(VideoAgentException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.UPLOAD_SESSION_STATE_CONFLICT));

        verify(sessions, never()).updateById(any(VideoUploadSessionEntity.class));
        verify(cleaner, never()).cleanupAfterCommit(any(VideoUploadSessionEntity.class));
    }

    private VideoUploadSessionEntity session(String status) {
        VideoUploadSessionEntity session = new VideoUploadSessionEntity();
        session.setId("u1");
        session.setUserId(7L);
        session.setFileName("lesson.mp4");
        session.setTitle("lesson");
        session.setFileSize(40L);
        session.setContentType("video/mp4");
        session.setChunkSize(16L);
        session.setTotalParts(3);
        session.setTempPrefix("upload-parts/u1");
        session.setObjectKey("videos/final.mp4");
        session.setStatus(status);
        session.setExpiresAt(LocalDateTime.now().plusHours(1));
        return session;
    }

    private String hash() {
        return "a".repeat(64);
    }
}
