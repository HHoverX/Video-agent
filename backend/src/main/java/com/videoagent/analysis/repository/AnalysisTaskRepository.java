package com.videoagent.analysis.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoagent.analysis.entity.AnalysisTaskEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

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

    @Select("""
        SELECT *
        FROM analysis_task
        WHERE status = 'PROCESSING'
          AND processing_at <= #{staleBefore}
        ORDER BY id ASC
        """)
    List<AnalysisTaskEntity> findStaleProcessing(@Param("staleBefore") LocalDateTime staleBefore);

    @Select("""
        SELECT *
        FROM analysis_task
        WHERE status = 'PROCESSING'
        ORDER BY id ASC
        """)
    List<AnalysisTaskEntity> findProcessingTasks();

    @Select("""
        SELECT *
        FROM analysis_task
        WHERE status = 'RETRY_WAITING'
          AND updated_at <= #{cutoff}
        ORDER BY id ASC
        """)
    List<AnalysisTaskEntity> findDueRetryWaiting(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Atomically claims a PENDING task (first attempt) or a RETRY_WAITING task
     * whose backoff has elapsed (retry attempt). On success the processing
     * generation is incremented so all subsequent writes by the winning worker
     * must carry this generation as a fencing token.
     */
    @Update("""
        UPDATE analysis_task
        SET status = 'PROCESSING',
            stage = #{stage},
            progress = #{progress},
            started_at = COALESCE(started_at, #{now}),
            processing_generation = processing_generation + 1,
            processing_at = #{now},
            error_code = NULL,
            error_message = NULL,
            updated_at = #{now}
        WHERE id = #{taskId}
          AND (
                (status = 'PENDING')
                OR
                (status = 'RETRY_WAITING' AND retry_not_before <= #{now})
              )
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
            processing_at = #{now},
            updated_at = #{now}
        WHERE id = #{taskId}
          AND status = 'PROCESSING'
          AND processing_generation = #{expectedGeneration}
        """)
    int updateProcessingProgress(
        @Param("taskId") long taskId,
        @Param("stage") String stage,
        @Param("progress") int progress,
        @Param("expectedGeneration") int expectedGeneration,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE analysis_task
        SET processing_at = #{now},
            updated_at = #{now}
        WHERE id = #{taskId}
          AND status = 'PROCESSING'
          AND processing_generation = #{expectedGeneration}
        """)
    int heartbeat(
        @Param("taskId") long taskId,
        @Param("expectedGeneration") int expectedGeneration,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE analysis_task
        SET status = 'SUCCESS',
            stage = 'DONE',
            progress = 100,
            error_code = NULL,
            error_message = NULL,
            processing_generation = processing_generation + 1,
            processing_at = NULL,
            retry_not_before = NULL,
            finished_at = #{now},
            updated_at = #{now}
        WHERE id = #{taskId}
          AND status = 'PROCESSING'
          AND processing_generation = #{expectedGeneration}
        """)
    int markSuccess(
        @Param("taskId") long taskId,
        @Param("expectedGeneration") int expectedGeneration,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE analysis_task
        SET status = 'FAILED',
            last_failure_stage = stage,
            stage = 'FAILED',
            error_code = #{errorCode},
            error_message = #{errorMessage},
            last_error_code = #{errorCode},
            last_error_message = #{errorMessage},
            processing_generation = processing_generation + 1,
            processing_at = NULL,
            retry_not_before = NULL,
            finished_at = #{now},
            updated_at = #{now}
        WHERE id = #{taskId}
          AND status = 'PROCESSING'
          AND processing_generation = #{expectedGeneration}
        """)
    int markFailedForGeneration(
        @Param("taskId") long taskId,
        @Param("expectedGeneration") int expectedGeneration,
        @Param("errorCode") String errorCode,
        @Param("errorMessage") String errorMessage,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE analysis_task
        SET status = 'FAILED',
            last_failure_stage = stage,
            stage = 'FAILED',
            error_code = #{errorCode},
            error_message = #{errorMessage},
            last_error_code = #{errorCode},
            last_error_message = #{errorMessage},
            processing_at = NULL,
            finished_at = #{now},
            updated_at = #{now}
        WHERE id = #{taskId}
          AND status IN ('PENDING', 'RETRY_WAITING')
        """)
    int markFailedIfNotStarted(
        @Param("taskId") long taskId,
        @Param("errorCode") String errorCode,
        @Param("errorMessage") String errorMessage,
        @Param("now") LocalDateTime now
    );

    /**
     * Moves a PROCESSING task to RETRY_WAITING for the next attempt, guarded by
     * generation fencing AND the retry budget (retry_count + 1 < maxAttempts).
     * Sets retry_not_before to the backoff deadline. Generation is incremented.
     * Returns 1 only when the transition happened.
     */
    @Update("""
        UPDATE analysis_task
        SET status = 'RETRY_WAITING',
            last_failure_stage = #{stage},
            stage = #{stage},
            error_code = #{errorCode},
            error_message = #{errorMessage},
            last_error_code = #{errorCode},
            last_error_message = #{errorMessage},
            retry_count = retry_count + 1,
            retry_not_before = #{nextAttemptAt},
            processing_generation = processing_generation + 1,
            processing_at = NULL,
            updated_at = #{now}
        WHERE id = #{taskId}
          AND status = 'PROCESSING'
          AND processing_generation = #{expectedGeneration}
          AND retry_count + 1 < #{maxAttempts}
        """)
    int markRetryWaitingForGeneration(
        @Param("taskId") long taskId,
        @Param("expectedGeneration") int expectedGeneration,
        @Param("stage") String stage,
        @Param("errorCode") String errorCode,
        @Param("errorMessage") String errorMessage,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
        @Param("maxAttempts") int maxAttempts,
        @Param("now") LocalDateTime now
    );

    /**
     * Moves a PROCESSING task straight to FAILED when the retry budget is
     * exhausted (retry_count + 1 >= maxAttempts). Generation is incremented so
     * the abandoned worker is fenced out. Returns 1 only when the transition
     * happened.
     */
    @Update("""
        UPDATE analysis_task
        SET status = 'FAILED',
            last_failure_stage = stage,
            stage = 'FAILED',
            error_code = #{errorCode},
            error_message = #{errorMessage},
            last_error_code = #{errorCode},
            last_error_message = #{errorMessage},
            retry_count = retry_count + 1,
            retry_not_before = NULL,
            processing_generation = processing_generation + 1,
            processing_at = NULL,
            finished_at = #{now},
            updated_at = #{now}
        WHERE id = #{taskId}
          AND status = 'PROCESSING'
          AND processing_generation = #{expectedGeneration}
          AND retry_count + 1 >= #{maxAttempts}
        """)
    int markFailedForBudgetExhausted(
        @Param("taskId") long taskId,
        @Param("expectedGeneration") int expectedGeneration,
        @Param("errorCode") String errorCode,
        @Param("errorMessage") String errorMessage,
        @Param("maxAttempts") int maxAttempts,
        @Param("now") LocalDateTime now
    );

    /**
     * Stale recovery that still has retry budget: reclaims a PROCESSING task
     * older than the lease into RETRY_WAITING, applying the same budget guard
     * and generation fencing as the normal retry transition. Returns 1 only
     * when the transition happened.
     */
    @Update("""
        UPDATE analysis_task
        SET status = 'RETRY_WAITING',
            last_failure_stage = stage,
            stage = 'RETRY_WAITING',
            error_code = #{errorCode},
            error_message = #{errorMessage},
            last_error_code = #{errorCode},
            last_error_message = #{errorMessage},
            retry_count = retry_count + 1,
            retry_not_before = #{nextAttemptAt},
            processing_generation = processing_generation + 1,
            processing_at = NULL,
            updated_at = #{now}
        WHERE id = #{taskId}
          AND status = 'PROCESSING'
          AND processing_at <= #{staleBefore}
          AND retry_count + 1 < #{maxAttempts}
        """)
    int reclaimStaleProcessingWithBudget(
        @Param("taskId") long taskId,
        @Param("errorCode") String errorCode,
        @Param("errorMessage") String errorMessage,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
        @Param("staleBefore") LocalDateTime staleBefore,
        @Param("maxAttempts") int maxAttempts,
        @Param("now") LocalDateTime now
    );

    /**
     * Stale recovery with no remaining budget: reclaims a stale PROCESSING task
     * directly into FAILED. Generation is incremented so the abandoned worker
     * is fenced out. Returns 1 only when the transition happened.
     */
    @Update("""
        UPDATE analysis_task
        SET status = 'FAILED',
            last_failure_stage = stage,
            stage = 'FAILED',
            error_code = #{errorCode},
            error_message = #{errorMessage},
            last_error_code = #{errorCode},
            last_error_message = #{errorMessage},
            retry_count = retry_count + 1,
            retry_not_before = NULL,
            processing_generation = processing_generation + 1,
            processing_at = NULL,
            finished_at = #{now},
            updated_at = #{now}
        WHERE id = #{taskId}
          AND status = 'PROCESSING'
          AND processing_at <= #{staleBefore}
          AND retry_count + 1 >= #{maxAttempts}
        """)
    int reclaimStaleProcessingExhausted(
        @Param("taskId") long taskId,
        @Param("errorCode") String errorCode,
        @Param("errorMessage") String errorMessage,
        @Param("staleBefore") LocalDateTime staleBefore,
        @Param("maxAttempts") int maxAttempts,
        @Param("now") LocalDateTime now
    );
}
