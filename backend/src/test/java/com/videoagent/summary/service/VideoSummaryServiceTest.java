package com.videoagent.summary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.asr.TranscriptSegment;
import com.videoagent.summary.dto.VideoChapterResponse;
import com.videoagent.summary.dto.VideoKeyPointResponse;
import com.videoagent.summary.entity.VideoChapterEntity;
import com.videoagent.summary.entity.VideoKeyPointEntity;
import com.videoagent.summary.entity.VideoSummaryEntity;
import com.videoagent.summary.provider.SummaryChapter;
import com.videoagent.summary.provider.SummaryKeyPoint;
import com.videoagent.summary.provider.VideoSummaryRequest;
import com.videoagent.summary.provider.VideoSummaryResult;
import com.videoagent.summary.repository.VideoChapterRepository;
import com.videoagent.summary.repository.VideoKeyPointRepository;
import com.videoagent.summary.repository.VideoSummaryRepository;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

class VideoSummaryServiceTest {

    private final VideoSummaryRepository summaryRepository = mock(VideoSummaryRepository.class);
    private final VideoChapterRepository chapterRepository = mock(VideoChapterRepository.class);
    private final VideoKeyPointRepository keyPointRepository = mock(VideoKeyPointRepository.class);
    private final VideoRepository videoRepository = mock(VideoRepository.class);
    private final VideoSummaryService service = new VideoSummaryService(
        summaryRepository,
        chapterRepository,
        keyPointRepository,
        videoRepository,
        new SummaryResultValidator()
    );

    @BeforeEach
    void setUp() {
        when(summaryRepository.insert(any(VideoSummaryEntity.class))).thenReturn(1);
        when(chapterRepository.insert(any(VideoChapterEntity.class))).thenReturn(1);
        when(keyPointRepository.insert(any(VideoKeyPointEntity.class))).thenReturn(1);
        when(videoRepository.selectById(7L)).thenReturn(new VideoEntity());
    }

    @Test
    void shouldPersistNormalizedSummaryChaptersAndKeyPointsInTimestampOrder() {
        AnalysisTaskEntity task = task();
        VideoSummaryRequest request = request();
        VideoSummaryResult result = new VideoSummaryResult(
            " overview ",
            List.of(
                new SummaryChapter("later", "later summary", 2_000, 4_000),
                new SummaryChapter("first", "first summary", 0, 2_000)
            ),
            List.of(
                new SummaryKeyPoint("later point", 2_000, 4_000),
                new SummaryKeyPoint("first point", 0, 2_000)
            )
        );

        service.replaceTaskResult(task, request, result);

        verify(keyPointRepository).deleteByTaskId(11L);
        verify(chapterRepository).deleteByTaskId(11L);
        verify(summaryRepository).deleteByTaskId(11L);

        ArgumentCaptor<VideoSummaryEntity> summaryCaptor =
            ArgumentCaptor.forClass(VideoSummaryEntity.class);
        verify(summaryRepository).insert(summaryCaptor.capture());
        assertThat(summaryCaptor.getValue().getOverview()).isEqualTo("overview");
        assertThat(summaryCaptor.getValue().getVideoId()).isEqualTo(7L);

        ArgumentCaptor<VideoChapterEntity> chapterCaptor =
            ArgumentCaptor.forClass(VideoChapterEntity.class);
        verify(chapterRepository, org.mockito.Mockito.times(2)).insert(chapterCaptor.capture());
        assertThat(chapterCaptor.getAllValues()).extracting(VideoChapterEntity::getChapterIndex)
            .containsExactly(0, 1);
        assertThat(chapterCaptor.getAllValues()).extracting(VideoChapterEntity::getTitle)
            .containsExactly("first", "later");

        ArgumentCaptor<VideoKeyPointEntity> pointCaptor =
            ArgumentCaptor.forClass(VideoKeyPointEntity.class);
        verify(keyPointRepository, org.mockito.Mockito.times(2)).insert(pointCaptor.capture());
        assertThat(pointCaptor.getAllValues()).extracting(VideoKeyPointEntity::getPointIndex)
            .containsExactly(0, 1);
        assertThat(pointCaptor.getAllValues()).extracting(VideoKeyPointEntity::getContent)
            .containsExactly("first point", "later point");
    }

    @Test
    void shouldReturnRepositoryOrderedChaptersAndKeyPoints() {
        when(chapterRepository.findLatestSuccessfulByVideoId(7L)).thenReturn(List.of(
            chapter(0, 0, "first"),
            chapter(1, 2_000, "second")
        ));
        when(keyPointRepository.findLatestSuccessfulByVideoId(7L)).thenReturn(List.of(
            point(0, 0, "first point"),
            point(1, 2_000, "second point")
        ));

        List<VideoChapterResponse> chapters = service.getChapters(7L);
        List<VideoKeyPointResponse> points = service.getKeyPoints(7L);

        assertThat(chapters).extracting(VideoChapterResponse::chapterIndex)
            .containsExactly(0, 1);
        assertThat(chapters).extracting(VideoChapterResponse::startMs)
            .containsExactly(0L, 2_000L);
        assertThat(points).extracting(VideoKeyPointResponse::pointIndex)
            .containsExactly(0, 1);
        assertThat(points).extracting(VideoKeyPointResponse::startMs)
            .containsExactly(0L, 2_000L);
    }

    private AnalysisTaskEntity task() {
        AnalysisTaskEntity task = new AnalysisTaskEntity();
        task.setId(11L);
        task.setVideoId(7L);
        return task;
    }

    private VideoSummaryRequest request() {
        return new VideoSummaryRequest(7L, 11L, List.of(
            new TranscriptSegment(0, 2_000, "first"),
            new TranscriptSegment(2_000, 4_000, "second")
        ));
    }

    private VideoChapterEntity chapter(int index, long startMs, String title) {
        VideoChapterEntity entity = new VideoChapterEntity();
        entity.setChapterIndex(index);
        entity.setTitle(title);
        entity.setSummary(title + " summary");
        entity.setStartMs(startMs);
        entity.setEndMs(startMs + 2_000);
        return entity;
    }

    private VideoKeyPointEntity point(int index, long startMs, String content) {
        VideoKeyPointEntity entity = new VideoKeyPointEntity();
        entity.setPointIndex(index);
        entity.setContent(content);
        entity.setStartMs(startMs);
        entity.setEndMs(startMs + 2_000);
        return entity;
    }
}
