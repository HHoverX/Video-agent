package com.videoagent.summary.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoagent.summary.entity.VideoSummaryEntity;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface VideoSummaryRepository extends BaseMapper<VideoSummaryEntity> {

    @Delete("DELETE FROM video_summary WHERE task_id = #{taskId}")
    int deleteByTaskId(@Param("taskId") long taskId);

    @Select("SELECT COUNT(*) FROM video_summary WHERE task_id = #{taskId}")
    long countByTaskId(@Param("taskId") long taskId);

    @Select("""
        SELECT summary.*
        FROM video_summary summary
        INNER JOIN analysis_task task ON task.id = summary.task_id
        WHERE summary.video_id = #{videoId}
          AND task.status = 'SUCCESS'
          AND summary.task_id = (
              SELECT MAX(latest.task_id)
              FROM video_summary latest
              INNER JOIN analysis_task latest_task ON latest_task.id = latest.task_id
              WHERE latest.video_id = #{videoId}
                AND latest_task.status = 'SUCCESS'
          )
        """)
    VideoSummaryEntity findLatestSuccessfulByVideoId(@Param("videoId") long videoId);
}
