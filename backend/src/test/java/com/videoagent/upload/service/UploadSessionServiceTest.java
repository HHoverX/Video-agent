package com.videoagent.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.storage.ObjectStorageService;
import com.videoagent.storage.StoredObject;
import com.videoagent.upload.dto.CompleteUploadPartRequest;
import com.videoagent.upload.dto.CreateUploadSessionRequest;
import com.videoagent.upload.dto.UploadSessionResponse;
import com.videoagent.upload.entity.VideoUploadPartEntity;
import com.videoagent.upload.entity.VideoUploadSessionEntity;
import com.videoagent.upload.repository.VideoUploadPartRepository;
import com.videoagent.upload.repository.VideoUploadSessionRepository;
import com.videoagent.video.service.VideoUploadProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

class UploadSessionServiceTest {

    private final VideoUploadSessionRepository sessions = mock(VideoUploadSessionRepository.class);
    private final VideoUploadPartRepository parts = mock(VideoUploadPartRepository.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final UploadTemporaryObjectCleaner cleaner = mock(UploadTemporaryObjectCleaner.class);
    private UploadSessionService service;

    @BeforeEach
    void setUp() {
        VideoUploadProperties properties = new VideoUploadProperties(
            DataSize.ofGigabytes(20), DataSize.ofMegabytes(16), DataSize.ofMegabytes(5),
            DataSize.ofMegabytes(128), 10_000, Duration.ofHours(24), Duration.ofMinutes(15), 3
        );
        service = new UploadSessionService(sessions, parts, storage, properties, cleaner);
    }

    @Test
    void shouldCreateServerOwnedSessionAndBoundPartCount() {
        when(sessions.insert(any(VideoUploadSessionEntity.class))).thenReturn(1);
        CreateUploadSessionRequest request = new CreateUploadSessionRequest(
            "lesson.mp4", "lesson", 40L * 1024 * 1024, "video/mp4", 16L * 1024 * 1024, null
        );

        UploadSessionResponse response = service.create(7L, request);

        assertThat(response.totalParts()).isEqualTo(3);
        assertThat(response.maxConcurrency()).isEqualTo(3);
        var captor = org.mockito.ArgumentCaptor.forClass(VideoUploadSessionEntity.class);
        verify(sessions).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getObjectKey()).startsWith("videos/").endsWith(".mp4");
        assertThat(captor.getValue().getTempPrefix()).isEqualTo("upload-parts/" + response.uploadId());
    }

    @Test
    void shouldResumeWithOnlyPersistedCompletedParts() {
        VideoUploadSessionEntity session = session("UPLOADING");
        when(sessions.findOwned("u1", 7L)).thenReturn(session);
        when(parts.findByUploadId("u1")).thenReturn(List.of(part(1, 16, "e1"), part(3, 8, "e3")));

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
        VideoUploadPartEntity existing = part(1, 16, "etag-1");
        when(sessions.findOwned("u1", 7L)).thenReturn(session);
        when(parts.findPart("u1", 1)).thenReturn(existing);
        when(storage.statObject("upload-parts/u1/part-00001"))
            .thenReturn(new StoredObject("upload-parts/u1/part-00001", 16, "etag-1", "application/octet-stream"));

        service.confirmPart(7L, "u1", 1, new CompleteUploadPartRequest(null));
        service.confirmPart(7L, "u1", 1, new CompleteUploadPartRequest(null));

        verify(parts, org.mockito.Mockito.times(2)).upsertCompleted(
            eq("u1"), eq(1), eq("upload-parts/u1/part-00001"), eq(16L), eq(16L),
            eq("etag-1"), eq(null), any(LocalDateTime.class)
        );
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
}
