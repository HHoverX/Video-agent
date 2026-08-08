package com.videoagent.video.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoagent.video.entity.VideoEntity;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface VideoRepository extends BaseMapper<VideoEntity> {
}
