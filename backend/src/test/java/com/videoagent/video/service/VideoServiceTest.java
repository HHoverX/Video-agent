package com.videoagent.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.storage.ObjectStorageService;
import com.videoagent.video.dto.VideoUploadResponse;
import com.videoagent.video.dto.VideoPageResponse;
import com.videoagent.video.dto.VideoResponse;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

class VideoServiceTest {

    private static final byte[] CONTENT = "uploaded-video-content".getBytes();

    private final VideoRepository videoRepository = mock(VideoRepository.class);
    private final VideoFileValidator fileValidator = mock(VideoFileValidator.class);
    private final ObjectStorageService storageService = mock(ObjectStorageService.class);
    private final VideoOwnershipService ownershipService = mock(VideoOwnershipService.class);
    private final VideoDeletionService deletionService = mock(VideoDeletionService.class);
    private VideoService videoService;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "video-test"),
            VideoEntity.class
        );
        videoService = new VideoService(
            videoRepository,
            fileValidator,
            storageService,
            ownershipService,
            deletionService
        );
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

        VideoUploadResponse response = videoService.upload(5L, file, "Demo");

        assertThat(response.videoId()).isEqualTo(42L);
        ArgumentCaptor<VideoEntity> entityCaptor = ArgumentCaptor.forClass(VideoEntity.class);
        verify(videoRepository).insert(entityCaptor.capture());
        VideoEntity saved = entityCaptor.getValue();
        assertThat(saved.getObjectKey()).matches("videos/\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]+\\.mp4");
        assertThat(saved.getFileHash()).hasSize(64);
        assertThat(saved.getStatus()).isEqualTo("UPLOADED");
        assertThat(saved.getUserId()).isEqualTo(5L);
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

        assertThatThrownBy(() -> videoService.upload(5L, file, null))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.VIDEO_UPLOAD_FAILED)
            );

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(storageService).removeObject(keyCaptor.capture());
        assertThat(keyCaptor.getValue()).startsWith("videos/").endsWith(".mp4");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnUserScopedPageWithKeywordAndDescendingOrder() {
        VideoEntity entity = video(42L, 5L, "Agent demo");
        when(videoRepository.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<VideoEntity> requested = invocation.getArgument(0);
            requested.setRecords(List.of(entity));
            requested.setTotal(1);
            return requested;
        });

        VideoPageResponse response = videoService.listVideos(5L, 1, 10, " agent ");

        assertThat(response.items()).extracting(VideoResponse::id).containsExactly(42L);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.total()).isEqualTo(1);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<VideoEntity>> queryCaptor =
            ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(videoRepository).selectPage(any(Page.class), queryCaptor.capture());
        assertThat(queryCaptor.getValue().getSqlSegment())
            .contains("user_id", "title", "created_at", "DESC");
    }

    @Test
    void shouldUpdateOnlyOwnedVideoTitle() {
        VideoEntity before = video(42L, 5L, "Old title");
        VideoEntity after = video(42L, 5L, "New title");
        when(ownershipService.requireOwned(42L, 5L)).thenReturn(before, after);
        when(videoRepository.update(any(), any())).thenReturn(1);

        VideoResponse response = videoService.updateTitle(42L, 5L, " New title ");

        assertThat(response.title()).isEqualTo("New title");
        verify(videoRepository).update(any(), any());
    }

    @Test
    void shouldDeleteStorageObjectAfterDatabaseDeletionAndTolerateCleanupFailure() {
        when(deletionService.deleteDatabaseRecords(42L, 5L)).thenReturn("videos/owned.mp4");

        videoService.deleteVideo(42L, 5L);

        verify(storageService).removeObject("videos/owned.mp4");

        when(deletionService.deleteDatabaseRecords(43L, 5L)).thenReturn("videos/orphan.mp4");
        doThrow(new RuntimeException("minio unavailable"))
            .when(storageService).removeObject("videos/orphan.mp4");
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> videoService.deleteVideo(43L, 5L));
    }

    private VideoEntity video(long id, long userId, String title) {
        LocalDateTime now = LocalDateTime.now();
        VideoEntity entity = new VideoEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setTitle(title);
        entity.setOriginalFilename("demo.mp4");
        entity.setFileSize(10L);
        entity.setMimeType("video/mp4");
        entity.setStatus("UPLOADED");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }
}
