package com.videoagent.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VideoDeletionServiceTest {

    private final VideoRepository videoRepository = mock(VideoRepository.class);
    private final AnalysisTaskRepository taskRepository = mock(AnalysisTaskRepository.class);
    private final VideoDeletionService service =
        new VideoDeletionService(videoRepository, taskRepository);

    @BeforeEach
    void setUp() {
        VideoEntity video = new VideoEntity();
        video.setId(42L);
        video.setUserId(5L);
        video.setObjectKey("videos/owned.mp4");
        when(videoRepository.selectOne(any())).thenReturn(video);
    }

    @Test
    void shouldDeleteCompletedTasksThenVideoInsideDatabaseBoundary() {
        when(taskRepository.selectCount(any())).thenReturn(0L);
        when(taskRepository.delete(any())).thenReturn(2);
        when(videoRepository.deleteById(42L)).thenReturn(1);

        assertThat(service.deleteDatabaseRecords(42L, 5L)).isEqualTo("videos/owned.mp4");

        verify(taskRepository).delete(any());
        verify(videoRepository).deleteById(42L);
    }

    @Test
    void shouldRejectDeleteWhilePendingOrProcessingTaskExists() {
        when(taskRepository.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.deleteDatabaseRecords(42L, 5L))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.VIDEO_ANALYSIS_IN_PROGRESS)
            );
        verify(videoRepository, never()).deleteById(42L);
    }

    @Test
    void shouldHideAnotherUsersVideoDuringDelete() {
        when(videoRepository.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.deleteDatabaseRecords(42L, 6L))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.VIDEO_NOT_FOUND)
            );
        verify(taskRepository, never()).selectCount(any());
    }
}
