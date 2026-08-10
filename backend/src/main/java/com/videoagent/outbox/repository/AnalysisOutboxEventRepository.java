package com.videoagent.outbox.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoagent.outbox.entity.AnalysisOutboxEventEntity;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AnalysisOutboxEventRepository extends BaseMapper<AnalysisOutboxEventEntity> {

    @Insert("""
        INSERT INTO analysis_outbox_event (
            event_key,
            event_type,
            task_id,
            video_id,
            payload,
            status,
            attempt_count,
            next_attempt_at,
            created_at,
            updated_at
        )
        VALUES (
            #{eventKey},
            #{eventType},
            #{taskId},
            #{videoId},
            #{payload},
            'PENDING',
            0,
            #{nextAttemptAt},
            #{now},
            #{now}
        )
        ON DUPLICATE KEY UPDATE updated_at = updated_at
        """)
    int insertPendingIfAbsent(
        @Param("eventKey") String eventKey,
        @Param("eventType") String eventType,
        @Param("taskId") long taskId,
        @Param("videoId") long videoId,
        @Param("payload") String payload,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
        @Param("now") LocalDateTime now
    );

    @Select("""
        SELECT *
        FROM analysis_outbox_event
        WHERE event_key = #{eventKey}
        LIMIT 1
        """)
    AnalysisOutboxEventEntity findByEventKey(@Param("eventKey") String eventKey);

    /**
     * Due query only ever returns PENDING events whose retry deadline has
     * elapsed. Every event that reaches PUBLISHED / EXHAUSTED / CANCELLED /
     * INVALID leaves this set, so a pile of dead records cannot starve the
     * batch for new events.
     */
    @Select("""
        SELECT *
        FROM analysis_outbox_event
        WHERE status = 'PENDING'
          AND next_attempt_at <= #{now}
        ORDER BY next_attempt_at ASC, id ASC
        LIMIT #{limit}
        """)
    List<AnalysisOutboxEventEntity> findDuePending(
        @Param("now") LocalDateTime now,
        @Param("limit") int limit
    );

    /**
     * Records one real producer.send failure. attempt_count is incremented only
     * here, so it reflects actual send attempts (never reopen operations).
     */
    @Update("""
        UPDATE analysis_outbox_event
        SET attempt_count = attempt_count + 1,
            next_attempt_at = #{nextAttemptAt},
            last_error = #{lastError},
            updated_at = #{now}
        WHERE id = #{eventId}
          AND status = 'PENDING'
        """)
    int markRetry(
        @Param("eventId") long eventId,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
        @Param("lastError") String lastError,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE analysis_outbox_event
        SET status = 'PUBLISHED',
            published_at = #{publishedAt},
            last_error = NULL,
            updated_at = #{now}
        WHERE id = #{eventId}
          AND status = 'PENDING'
        """)
    int markPublished(
        @Param("eventId") long eventId,
        @Param("publishedAt") LocalDateTime publishedAt,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE analysis_outbox_event
        SET status = 'EXHAUSTED',
            last_error = #{lastError},
            updated_at = #{now}
        WHERE id = #{eventId}
          AND status = 'PENDING'
        """)
    int markExhausted(
        @Param("eventId") long eventId,
        @Param("lastError") String lastError,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE analysis_outbox_event
        SET status = 'CANCELLED',
            last_error = #{lastError},
            updated_at = #{now}
        WHERE id = #{eventId}
          AND status = 'PENDING'
        """)
    int markCancelled(
        @Param("eventId") long eventId,
        @Param("lastError") String lastError,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE analysis_outbox_event
        SET status = 'INVALID',
            last_error = #{lastError},
            updated_at = #{now}
        WHERE id = #{eventId}
          AND status = 'PENDING'
        """)
    int markInvalid(
        @Param("eventId") long eventId,
        @Param("lastError") String lastError,
        @Param("now") LocalDateTime now
    );

    /**
     * Re-opens a PUBLISHED event back to PENDING without consuming a publish
     * attempt. attempt_count therefore reflects real producer.send attempts
     * only, and a reopen does not push the event toward EXHAUSTED.
     */
    @Update("""
        UPDATE analysis_outbox_event
        SET status = 'PENDING',
            next_attempt_at = #{nextAttemptAt},
            last_error = NULL,
            updated_at = #{now}
        WHERE id = #{eventId}
          AND status = 'PUBLISHED'
        """)
    int reopenPublished(
        @Param("eventId") long eventId,
        @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
        @Param("now") LocalDateTime now
    );
}
