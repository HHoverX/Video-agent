package com.videoagent.transcript.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoagent.transcript.entity.VideoTranscriptSegmentEntity;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface VideoTranscriptSegmentRepository extends BaseMapper<VideoTranscriptSegmentEntity> {

    @Delete("DELETE FROM video_transcript_segment WHERE task_id = #{taskId}")
    int deleteByTaskId(@Param("taskId") long taskId);

    @Select("SELECT COUNT(*) FROM video_transcript_segment WHERE task_id = #{taskId}")
    long countByTaskId(@Param("taskId") long taskId);

    @Select("""
        SELECT *
        FROM video_transcript_segment
        WHERE task_id = #{taskId}
        ORDER BY segment_index ASC, start_ms ASC, id ASC
        """)
    List<VideoTranscriptSegmentEntity> findByTaskId(@Param("taskId") long taskId);

    @Select("""
        SELECT segment.*
        FROM video_transcript_segment segment
        INNER JOIN analysis_task task ON task.id = segment.task_id
        WHERE segment.video_id = #{videoId}
          AND task.status = 'SUCCESS'
          AND segment.task_id = (
              SELECT MAX(latest.task_id)
              FROM video_transcript_segment latest
              INNER JOIN analysis_task latest_task ON latest_task.id = latest.task_id
              WHERE latest.video_id = #{videoId}
                AND latest_task.status = 'SUCCESS'
          )
        ORDER BY segment.segment_index ASC, segment.start_ms ASC, segment.id ASC
        """)
    List<VideoTranscriptSegmentEntity> findLatestSuccessfulByVideoId(@Param("videoId") long videoId);
}
