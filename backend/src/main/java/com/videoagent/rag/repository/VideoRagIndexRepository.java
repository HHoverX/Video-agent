package com.videoagent.rag.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoagent.rag.entity.VideoRagIndexEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface VideoRagIndexRepository extends BaseMapper<VideoRagIndexEntity> {

    @Select("""
        SELECT *
        FROM video_rag_index
        WHERE video_id = #{videoId}
        LIMIT 1
        """)
    VideoRagIndexEntity findByVideoId(@Param("videoId") long videoId);

    /**
     * Atomically claims the index for a build. Only NOT_BUILT or FAILED or
     * READY (rebuild) indexes can move to BUILDING, which prevents two
     * concurrent builds from double-writing Qdrant.
     */
    @Update("""
        UPDATE video_rag_index
        SET status = 'BUILDING',
            last_error_code = NULL,
            last_error_message = NULL,
            updated_at = #{now}
        WHERE id = #{id}
          AND status IN ('NOT_BUILT', 'FAILED', 'READY')
        """)
    int claimBuilding(@Param("id") long id, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE video_rag_index
        SET status = 'READY',
            chunk_count = #{chunkCount},
            updated_at = #{now}
        WHERE id = #{id}
          AND status = 'BUILDING'
        """)
    int markReady(
        @Param("id") long id,
        @Param("chunkCount") int chunkCount,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE video_rag_index
        SET status = 'FAILED',
            last_error_code = #{errorCode},
            last_error_message = #{errorMessage},
            updated_at = #{now}
        WHERE id = #{id}
          AND status = 'BUILDING'
        """)
    int markFailed(
        @Param("id") long id,
        @Param("errorCode") String errorCode,
        @Param("errorMessage") String errorMessage,
        @Param("now") LocalDateTime now
    );
}
