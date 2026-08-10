package com.videoagent.video.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.video.entity.VideoEntity;
import com.videoagent.video.repository.VideoRepository;

import org.springframework.stereotype.Service;

@Service
public class VideoOwnershipService {

    private final VideoRepository videoRepository;

    public VideoOwnershipService(VideoRepository videoRepository) {
        this.videoRepository = videoRepository;
    }

    public VideoEntity requireOwned(long videoId, long userId) {
        VideoEntity video = findOwned(videoId, userId);
        if (video == null) {
            throw new VideoAgentException(ErrorCode.VIDEO_NOT_FOUND);
        }
        return video;
    }

    public boolean isOwned(long videoId, long userId) {
        return findOwned(videoId, userId) != null;
    }

    private VideoEntity findOwned(long videoId, long userId) {
        return videoRepository.selectOne(
            Wrappers.<VideoEntity>lambdaQuery()
                .eq(VideoEntity::getId, videoId)
                .eq(VideoEntity::getUserId, userId)
                .last("LIMIT 1")
        );
    }
}
