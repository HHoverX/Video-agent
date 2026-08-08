package com.videoagent.analysis.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoagent.analysis.entity.AnalysisTaskEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AnalysisTaskRepository extends BaseMapper<AnalysisTaskEntity> {

    @Select("""
        SELECT *
        FROM analysis_task
        WHERE video_id = #{videoId}
          AND analysis_type = #{analysisType}
          AND model_version = #{modelVersion}
        LIMIT 1
        """)
    AnalysisTaskEntity findByBusinessKey(
        @Param("videoId") long videoId,
        @Param("analysisType") String analysisType,
        @Param("modelVersion") String modelVersion
    );

    @Update("""
        UPDATE analysis_task
        SET status = 'PROCESSING',
            stage = #{stage},
            progress = #{progress},
            started_at = COALESCE(started_at, #{now}),
            updated_at = #{now}
        WHERE id = #{taskId}
          AND status = 'PENDING'
        """)
    int claimPending(
        @Param("taskId") long taskId,
        @Param("stage") String stage,
        @Param("progress") int progress,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE analysis_task
        SET stage = #{stage},
            progress = #{progress},
            updated_at = #{now}
        WHERE id = #{taskId}
          AND status = 'PROCESSING'
        """)
    int updateProcessingProgress(
        @Param("taskId") long taskId,
        @Param("stage") String stage,
        @Param("progress") int progress,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE analysis_task
        SET status = 'SUCCESS',
            stage = 'DONE',
            progress = 100,
            error_code = NULL,
            error_message = NULL,
            finished_at = #{now},
            updated_at = #{now}
        WHERE id = #{taskId}
          AND status = 'PROCESSING'
        """)
    int markSuccess(@Param("taskId") long taskId, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE analysis_task
        SET status = 'FAILED',
            stage = 'FAILED',
            error_code = #{errorCode},
            error_message = #{errorMessage},
            finished_at = #{now},
            updated_at = #{now}
        WHERE id = #{taskId}
          AND status <> 'SUCCESS'
        """)
    int markFailed(
        @Param("taskId") long taskId,
        @Param("errorCode") String errorCode,
        @Param("errorMessage") String errorMessage,
        @Param("now") LocalDateTime now
    );
}
