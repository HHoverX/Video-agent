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
     * Atomically claims the index for a build. A live BUILDING lease cannot be
     * stolen; a crashed builder becomes reclaimable only after staleBefore.
     */
    @Update("""
        UPDATE video_rag_index
        SET status = 'BUILDING',
            build_token = #{buildToken},
            build_started_at = #{now},
            last_error_code = NULL,
            last_error_message = NULL,
            updated_at = #{now}
        WHERE id = #{id}
          AND (
                status IN ('NOT_BUILT', 'FAILED', 'READY')
                OR (status = 'BUILDING' AND (build_started_at IS NULL OR build_started_at <= #{staleBefore}))
              )
        """)
    int claimBuilding(
        @Param("id") long id,
        @Param("buildToken") String buildToken,
        @Param("staleBefore") LocalDateTime staleBefore,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE video_rag_index
        SET status = 'READY',
            chunk_count = #{chunkCount},
            build_started_at = NULL,
            updated_at = #{now}
        WHERE id = #{id}
          AND status = 'BUILDING'
          AND build_token = #{buildToken}
        """)
    int markReady(
        @Param("id") long id,
        @Param("buildToken") String buildToken,
        @Param("chunkCount") int chunkCount,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE video_rag_index
        SET status = 'FAILED',
            last_error_code = #{errorCode},
            last_error_message = #{errorMessage},
            build_started_at = NULL,
            updated_at = #{now}
        WHERE id = #{id}
          AND status = 'BUILDING'
          AND build_token = #{buildToken}
        """)
    int markFailed(
        @Param("id") long id,
        @Param("buildToken") String buildToken,
        @Param("errorCode") String errorCode,
        @Param("errorMessage") String errorMessage,
        @Param("now") LocalDateTime now
    );
}
