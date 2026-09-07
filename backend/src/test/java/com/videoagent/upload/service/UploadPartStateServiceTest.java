package com.videoagent.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.storage.ObjectStorageService;
import com.videoagent.storage.StoredObject;
import com.videoagent.upload.entity.VideoUploadSessionEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;

class UploadPartStateServiceTest {

    private final UploadPartBitmapStore bitmapStore = mock(UploadPartBitmapStore.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final UploadPartStateService service = new UploadPartStateService(bitmapStore, storage);
    private VideoUploadSessionEntity session;

    @BeforeEach
    void setUp() {
        session = new VideoUploadSessionEntity();
        session.setId("upload-1");
        session.setTempPrefix("uploads/upload-1/parts");
        session.setFileSize(12L);
        session.setChunkSize(5L);
        session.setTotalParts(3);
        session.setExpiresAt(LocalDateTime.now().plusHours(2));
    }

    @Test
    void shouldReadExistingBitmapWithoutMinioScan() {
        when(bitmapStore.exists("upload-1")).thenReturn(true);
        when(bitmapStore.readCompleted("upload-1", 0, 3)).thenReturn(List.of(0, 2));

        assertThat(service.completedPartNumbers(session)).containsExactly(0, 2);

        verifyNoInteractions(storage);
        verify(bitmapStore, never()).rebuild("upload-1", List.of(0, 2), session.getExpiresAt());
    }

    @Test
    void shouldRebuildMissingBitmapOnlyFromCorrectlySizedMinioParts() {
        when(bitmapStore.exists("upload-1")).thenReturn(false);
        when(storage.statObjectIfExists("uploads/upload-1/parts/0"))
            .thenReturn(new StoredObject("part-0", 5L, "e0", "video/mp4"));
        when(storage.statObjectIfExists("uploads/upload-1/parts/1"))
            .thenReturn(new StoredObject("part-1", 4L, "e1", "video/mp4"));
        when(storage.statObjectIfExists("uploads/upload-1/parts/2"))
            .thenReturn(new StoredObject("part-2", 2L, "e2", "video/mp4"));

        assertThat(service.completedPartNumbers(session)).containsExactly(0, 2);

        verify(bitmapStore).rebuild("upload-1", List.of(0, 2), session.getExpiresAt());
    }

    @Test
    void shouldRebuildLegacyOneBasedSessionFromLegacyMinioKeysWithoutPartRows() {
        session.setTempPrefix("upload-parts/upload-1");
        when(bitmapStore.exists("upload-1")).thenReturn(false);
        when(storage.statObjectIfExists("upload-parts/upload-1/part-00001"))
            .thenReturn(new StoredObject("legacy-1", 5L, "e1", "video/mp4"));
        when(storage.statObjectIfExists("upload-parts/upload-1/part-00003"))
            .thenReturn(new StoredObject("legacy-3", 2L, "e3", "video/mp4"));

        assertThat(service.completedPartNumbers(session)).containsExactly(1, 3);

        verify(bitmapStore).rebuild("upload-1", List.of(1, 3), session.getExpiresAt());
    }

    @Test
    void shouldScanMinioWhenRedisIsUnavailableWithoutDeletingParts() {
        when(bitmapStore.exists("upload-1"))
            .thenThrow(new DataAccessResourceFailureException("redis down"));
        when(storage.statObjectIfExists("uploads/upload-1/parts/0"))
            .thenReturn(new StoredObject("part-0", 5L, "e0", "video/mp4"));

        assertThat(service.completedPartNumbers(session)).containsExactly(0);

        verify(storage, never()).removeObject("uploads/upload-1/parts/0");
    }

    @Test
    void shouldRejectIncompleteBitmapBeforeMinioValidation() {
        when(bitmapStore.exists("upload-1")).thenReturn(true);
        when(bitmapStore.count("upload-1")).thenReturn(2L);

        assertThatThrownBy(() -> service.requirePossiblyComplete(session))
            .isInstanceOf(VideoAgentException.class)
            .hasMessageContaining("尚未上传完成");

        verifyNoInteractions(storage);
    }

    @Test
    void shouldLetCompleteFallBackToFinalMinioValidationWhenRedisIsUnavailable() {
        when(bitmapStore.exists("upload-1"))
            .thenThrow(new DataAccessResourceFailureException("redis down"));

        service.requirePossiblyComplete(session);

        verifyNoInteractions(storage);
    }
}
