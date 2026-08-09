package com.videoagent.summary.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoagent.summary.entity.VideoChapterEntity;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface VideoChapterRepository extends BaseMapper<VideoChapterEntity> {

    @Delete("DELETE FROM video_chapter WHERE task_id = #{taskId}")
    int deleteByTaskId(@Param("taskId") long taskId);

    @Select("""
        SELECT chapter.*
        FROM video_chapter chapter
        INNER JOIN analysis_task task ON task.id = chapter.task_id
        WHERE chapter.video_id = #{videoId}
          AND task.status = 'SUCCESS'
          AND chapter.task_id = (
              SELECT MAX(latest.task_id)
              FROM video_chapter latest
              INNER JOIN analysis_task latest_task ON latest_task.id = latest.task_id
              WHERE latest.video_id = #{videoId}
                AND latest_task.status = 'SUCCESS'
          )
        ORDER BY chapter.chapter_index ASC, chapter.start_ms ASC, chapter.id ASC
        """)
    List<VideoChapterEntity> findLatestSuccessfulByVideoId(@Param("videoId") long videoId);
}
