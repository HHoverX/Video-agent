package com.videoagent.summary.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoagent.summary.entity.VideoKeyPointEntity;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface VideoKeyPointRepository extends BaseMapper<VideoKeyPointEntity> {

    @Delete("DELETE FROM video_key_point WHERE task_id = #{taskId}")
    int deleteByTaskId(@Param("taskId") long taskId);

    @Select("""
        SELECT point.*
        FROM video_key_point point
        INNER JOIN analysis_task task ON task.id = point.task_id
        WHERE point.video_id = #{videoId}
          AND task.status = 'SUCCESS'
          AND point.task_id = (
              SELECT MAX(latest.task_id)
              FROM video_key_point latest
              INNER JOIN analysis_task latest_task ON latest_task.id = latest.task_id
              WHERE latest.video_id = #{videoId}
                AND latest_task.status = 'SUCCESS'
          )
        ORDER BY point.point_index ASC, point.start_ms ASC, point.id ASC
        """)
    List<VideoKeyPointEntity> findLatestSuccessfulByVideoId(@Param("videoId") long videoId);
}
