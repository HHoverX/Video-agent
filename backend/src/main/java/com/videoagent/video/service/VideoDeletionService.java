package com.videoagent.video.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videoagent.analysis.entity.AnalysisStatus;
import com.videoagent.analysis.entity.AnalysisTaskEntity;
import com.videoagent.analysis.repository.AnalysisTaskRepository;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VideoDeletionService {

    private final VideoRepository videoRepository;
    private final AnalysisTaskRepository taskRepository;

    public VideoDeletionService(
        VideoRepository videoRepository,
        AnalysisTaskRepository taskRepository
    ) {
        this.videoRepository = videoRepository;
        this.taskRepository = taskRepository;
    }

    @Transactional
    public String deleteDatabaseRecords(long videoId, long userId) {
        VideoEntity video = videoRepository.selectOne(
            Wrappers.<VideoEntity>lambdaQuery()
                .eq(VideoEntity::getId, videoId)
                .eq(VideoEntity::getUserId, userId)
                .last("FOR UPDATE")
        );
        if (video == null) {
            throw new VideoAgentException(ErrorCode.VIDEO_NOT_FOUND);
        }

        Long activeTasks = taskRepository.selectCount(
            Wrappers.<AnalysisTaskEntity>lambdaQuery()
                .eq(AnalysisTaskEntity::getVideoId, videoId)
                .in(
                    AnalysisTaskEntity::getStatus,
                    AnalysisStatus.PENDING.name(),
                    AnalysisStatus.PROCESSING.name(),
                    AnalysisStatus.RETRY_WAITING.name()
                )
        );
        if (activeTasks != null && activeTasks > 0) {
            throw new VideoAgentException(ErrorCode.VIDEO_ANALYSIS_IN_PROGRESS);
        }

        taskRepository.delete(
            Wrappers.<AnalysisTaskEntity>lambdaQuery()
                .eq(AnalysisTaskEntity::getVideoId, videoId)
        );
        if (videoRepository.deleteById(videoId) != 1) {
            throw new VideoAgentException(ErrorCode.INTERNAL_ERROR, "视频数据库记录删除失败");
        }
        return video.getObjectKey();
    }
}
