package com.videoagent.transcript.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.asr.TranscriptSegment;
import com.videoagent.asr.TranscriptionResult;
import com.videoagent.transcript.dto.TranscriptSegmentResponse;
import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;
import com.videoagent.transcript.repository.VideoTranscriptSegmentRepository;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

class TranscriptServiceTest {

    private final VideoTranscriptSegmentRepository repository =
        mock(VideoTranscriptSegmentRepository.class);
    private final VideoRepository videoRepository = mock(VideoRepository.class);
    private final TranscriptService service = new TranscriptService(repository, videoRepository);

    @Test
    void shouldPersistOneRowPerTimestampSegmentInOrder() {
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setId(101L);
        task.setVideoId(7L);
        TranscriptionResult result = new TranscriptionResult(List.of(
            new TranscriptSegment(0, 2_000, "first"),
            new TranscriptSegment(2_000, 4_000, "second"),
            new TranscriptSegment(4_000, 6_000, "third")
        ));
        when(repository.insert(any(VideoTranscriptSegmentEntity.class))).thenReturn(1);

        service.replaceTaskSegments(task, result);

        verify(repository).deleteByTaskId(101L);
        ArgumentCaptor<VideoTranscriptSegmentEntity> captor =
            ArgumentCaptor.forClass(VideoTranscriptSegmentEntity.class);
        verify(repository, times(3)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(VideoTranscriptSegmentEntity::getSegmentIndex)
            .containsExactly(0, 1, 2);
        assertThat(captor.getAllValues()).extracting(VideoTranscriptSegmentEntity::getStartMs)
            .containsExactly(0L, 2_000L, 4_000L);
        assertThat(captor.getAllValues()).allSatisfy(entity -> {
            assertThat(entity.getTaskId()).isEqualTo(101L);
            assertThat(entity.getVideoId()).isEqualTo(7L);
            assertThat(entity.getCreatedAt()).isNotNull();
        });
    }

    @Test
    void shouldReturnRepositoryOrderAndEmptyListWhenTranscriptIsNotReady() {
        when(videoRepository.selectById(7L)).thenReturn(new VideoEntity());
        when(repository.findLatestSuccessfulByVideoId(7L)).thenReturn(List.of(
            entity(2_000, 4_000, "second"),
            entity(4_000, 6_000, "third")
        ));

        List<TranscriptSegmentResponse> response = service.getVideoTranscript(7L);

        assertThat(response).extracting(TranscriptSegmentResponse::startMs)
            .containsExactly(2_000L, 4_000L);

        when(repository.findLatestSuccessfulByVideoId(7L)).thenReturn(List.of());
        assertThat(service.getVideoTranscript(7L)).isEmpty();
    }

    private VideoTranscriptSegmentEntity entity(long startMs, long endMs, String text) {
        VideoTranscriptSegmentEntity entity = new VideoTranscriptSegmentEntity();
        entity.setStartMs(startMs);
        entity.setEndMs(endMs);
        entity.setText(text);
        return entity;
    }
}
