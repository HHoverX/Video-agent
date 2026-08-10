package com.videoagent.summary.service;

import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.summary.dto.VideoChapterResponse;
import com.videoagent.summary.dto.VideoKeyPointResponse;
import com.videoagent.summary.dto.VideoSummaryResponse;
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
import com.videoagent.video.service.VideoOwnershipService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class VideoSummaryService {

    private final VideoSummaryRepository summaryRepository;
    private final VideoChapterRepository chapterRepository;
    private final VideoKeyPointRepository keyPointRepository;
    private final VideoOwnershipService ownershipService;
    private final SummaryResultValidator validator;

    public VideoSummaryService(
        VideoSummaryRepository summaryRepository,
        VideoChapterRepository chapterRepository,
        VideoKeyPointRepository keyPointRepository,
        VideoOwnershipService ownershipService,
        SummaryResultValidator validator
    ) {
        this.summaryRepository = summaryRepository;
        this.chapterRepository = chapterRepository;
        this.keyPointRepository = keyPointRepository;
        this.ownershipService = ownershipService;
        this.validator = validator;
    }

    @Transactional
    public void replaceTaskResult(
        AnalysisTaskEntity task,
        VideoSummaryRequest request,
        VideoSummaryResult rawResult
    ) {
        VideoSummaryResult result = validator.validate(request, rawResult);
        keyPointRepository.deleteByTaskId(task.getId());
        chapterRepository.deleteByTaskId(task.getId());
        summaryRepository.deleteByTaskId(task.getId());

        LocalDateTime now = LocalDateTime.now();
        VideoSummaryEntity summary = new VideoSummaryEntity();
        summary.setVideoId(task.getVideoId());
        summary.setTaskId(task.getId());
        summary.setOverview(result.overview());
        summary.setCreatedAt(now);
        summary.setUpdatedAt(now);
        requireInsert(summaryRepository.insert(summary), "overview 保存失败");

        for (int index = 0; index < result.chapters().size(); index++) {
            requireInsert(
                chapterRepository.insert(chapter(task, index, result.chapters().get(index))),
                "chapter 保存失败"
            );
        }
        for (int index = 0; index < result.keyPoints().size(); index++) {
            requireInsert(
                keyPointRepository.insert(keyPoint(task, index, result.keyPoints().get(index))),
                "key point 保存失败"
            );
        }
    }

    /**
     * Durable resume basis: whether a summary row already exists for this task
     * in MySQL. Used to decide whether the LLM must run again or the existing
     * summary/chapters/key points can be reused.
     */
    public boolean taskHasPersistedSummary(long taskId) {
        return summaryRepository.countByTaskId(taskId) > 0;
    }

    @Transactional(readOnly = true)
    public Optional<VideoSummaryResponse> getSummary(long videoId, long userId) {
        ownershipService.requireOwned(videoId, userId);
        return Optional.ofNullable(summaryRepository.findLatestSuccessfulByVideoId(videoId))
            .map(VideoSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public List<VideoChapterResponse> getChapters(long videoId, long userId) {
        ownershipService.requireOwned(videoId, userId);
        return chapterRepository.findLatestSuccessfulByVideoId(videoId).stream()
            .map(VideoChapterResponse::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<VideoKeyPointResponse> getKeyPoints(long videoId, long userId) {
        ownershipService.requireOwned(videoId, userId);
        return keyPointRepository.findLatestSuccessfulByVideoId(videoId).stream()
            .map(VideoKeyPointResponse::from)
            .toList();
    }

    private VideoChapterEntity chapter(AnalysisTaskEntity task, int index, SummaryChapter chapter) {
        VideoChapterEntity entity = new VideoChapterEntity();
        entity.setVideoId(task.getVideoId());
        entity.setTaskId(task.getId());
        entity.setChapterIndex(index);
        entity.setTitle(chapter.title());
        entity.setSummary(chapter.summary());
        entity.setStartMs(chapter.startMs());
        entity.setEndMs(chapter.endMs());
        return entity;
    }

    private VideoKeyPointEntity keyPoint(AnalysisTaskEntity task, int index, SummaryKeyPoint point) {
        VideoKeyPointEntity entity = new VideoKeyPointEntity();
        entity.setVideoId(task.getVideoId());
        entity.setTaskId(task.getId());
        entity.setPointIndex(index);
        entity.setContent(point.content());
        entity.setStartMs(point.startMs());
        entity.setEndMs(point.endMs());
        return entity;
    }

    private void requireInsert(int inserted, String message) {
        if (inserted != 1) {
            throw new VideoAgentException(ErrorCode.SUMMARY_PERSISTENCE_FAILED, message);
        }
    }
}
