package com.videoagent.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.storage.ObjectStorageService;
import com.videoagent.video.dto.VideoUploadResponse;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;

class VideoServiceTest {

    private static final byte[] CONTENT = "uploaded-video-content".getBytes();

    private final VideoRepository videoRepository = mock(VideoRepository.class);
    private final VideoFileValidator fileValidator = mock(VideoFileValidator.class);
    private final ObjectStorageService storageService = mock(ObjectStorageService.class);
    private VideoService videoService;

    @BeforeEach
    void setUp() {
        videoService = new VideoService(videoRepository, fileValidator, storageService);
        doAnswer(invocation -> {
            InputStream stream = invocation.getArgument(1);
            stream.readAllBytes();
            return null;
        }).when(storageService).putObject(anyString(), any(InputStream.class), eq((long) CONTENT.length), eq("video/mp4"));
    }

    @Test
    void shouldUploadObjectThenSaveMetadata() {
        MockMultipartFile file = new MockMultipartFile("file", "demo.mp4", "video/mp4", CONTENT);
        when(fileValidator.validate(file, "Demo"))
            .thenReturn(new ValidatedVideoFile("Demo", "demo.mp4", "video/mp4", CONTENT.length));
        when(videoRepository.insert(any(VideoEntity.class))).thenAnswer(invocation -> {
            VideoEntity entity = invocation.getArgument(0);
            entity.setId(42L);
            return 1;
        });

        VideoUploadResponse response = videoService.upload(file, "Demo");

        assertThat(response.videoId()).isEqualTo(42L);
        ArgumentCaptor<VideoEntity> entityCaptor = ArgumentCaptor.forClass(VideoEntity.class);
        verify(videoRepository).insert(entityCaptor.capture());
        VideoEntity saved = entityCaptor.getValue();
        assertThat(saved.getObjectKey()).matches("videos/\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]+\\.mp4");
        assertThat(saved.getFileHash()).hasSize(64);
        assertThat(saved.getStatus()).isEqualTo("UPLOADED");
        verify(storageService).putObject(
            eq(saved.getObjectKey()),
            any(InputStream.class),
            eq((long) CONTENT.length),
            eq("video/mp4")
        );
    }

    @Test
    void shouldRemoveObjectWhenDatabaseInsertFails() {
        MockMultipartFile file = new MockMultipartFile("file", "demo.mp4", "video/mp4", CONTENT);
        when(fileValidator.validate(file, null))
            .thenReturn(new ValidatedVideoFile("demo", "demo.mp4", "video/mp4", CONTENT.length));
        when(videoRepository.insert(any(VideoEntity.class))).thenThrow(new RuntimeException("database unavailable"));

        assertThatThrownBy(() -> videoService.upload(file, null))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.VIDEO_UPLOAD_FAILED)
            );

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(storageService).removeObject(keyCaptor.capture());
        assertThat(keyCaptor.getValue()).startsWith("videos/").endsWith(".mp4");
    }
}
