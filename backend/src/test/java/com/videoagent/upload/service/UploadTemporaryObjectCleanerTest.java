package com.videoagent.upload.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.videoagent.storage.ObjectStorageService;
import com.videoagent.upload.entity.VideoUploadSessionEntity;
import com.videoagent.upload.repository.VideoUploadSessionRepository;

import org.junit.jupiter.api.Test;

class UploadTemporaryObjectCleanerTest {

    private final VideoUploadSessionRepository sessions = mock(VideoUploadSessionRepository.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final UploadTemporaryObjectCleaner cleaner = new UploadTemporaryObjectCleaner(sessions, storage);

    @Test
    void shouldCleanExpiredPartsAndUncommittedComposedObject() {
        VideoUploadSessionEntity expired = session("EXPIRED", null);

        cleaner.cleanupNow(expired);

        verify(storage).removeObject("upload-parts/u1/part-00001");
        verify(storage).removeObject("upload-parts/u1/part-00002");
        verify(storage).removeObject("videos/final.mp4");
        verify(sessions).markTemporaryObjectsCleaned(org.mockito.ArgumentMatchers.eq("u1"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldNeverDeleteCompletedFormalVideo() {
        VideoUploadSessionEntity completed = session("COMPLETED", 42L);

        cleaner.cleanupNow(completed);

        verify(storage).removeObject("upload-parts/u1/part-00001");
        verify(storage).removeObject("upload-parts/u1/part-00002");
        verify(storage, never()).removeObject("videos/final.mp4");
    }

    private VideoUploadSessionEntity session(String status, Long videoId) {
        VideoUploadSessionEntity session = new VideoUploadSessionEntity();
        session.setId("u1");
        session.setTempPrefix("upload-parts/u1");
        session.setObjectKey("videos/final.mp4");
        session.setTotalParts(2);
        session.setStatus(status);
        session.setVideoId(videoId);
        return session;
    }
}
