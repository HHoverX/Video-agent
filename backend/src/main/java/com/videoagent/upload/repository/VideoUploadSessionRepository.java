package com.videoagent.upload.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoagent.upload.entity.VideoUploadSessionEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface VideoUploadSessionRepository extends BaseMapper<VideoUploadSessionEntity> {

    @Select("SELECT * FROM video_upload_session WHERE id = #{uploadId} AND user_id = #{userId} LIMIT 1")
    VideoUploadSessionEntity findOwned(@Param("uploadId") String uploadId, @Param("userId") long userId);

    @Select("SELECT * FROM video_upload_session WHERE id = #{uploadId} FOR UPDATE")
    VideoUploadSessionEntity lockById(@Param("uploadId") String uploadId);

    @Select("""
        SELECT * FROM video_upload_session
        WHERE status IN ('CREATED', 'UPLOADING', 'FAILED')
          AND expires_at <= #{now}
        ORDER BY expires_at ASC
        LIMIT #{limit}
        """)
    List<VideoUploadSessionEntity> findExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
        UPDATE video_upload_session
        SET status = 'EXPIRED', updated_at = #{now}
        WHERE id = #{uploadId}
          AND status IN ('CREATED', 'UPLOADING', 'FAILED')
          AND expires_at <= #{now}
        """)
    int markExpired(@Param("uploadId") String uploadId, @Param("now") LocalDateTime now);

    @Select("""
        SELECT * FROM video_upload_session
        WHERE status IN ('COMPLETED', 'CANCELLED', 'EXPIRED')
          AND temp_cleaned_at IS NULL
        ORDER BY updated_at ASC
        LIMIT #{limit}
        """)
    List<VideoUploadSessionEntity> findCleanupPending(@Param("limit") int limit);

    @Update("""
        UPDATE video_upload_session
        SET temp_cleaned_at = #{now}, updated_at = #{now}
        WHERE id = #{uploadId} AND temp_cleaned_at IS NULL
        """)
    int markTemporaryObjectsCleaned(
        @Param("uploadId") String uploadId,
        @Param("now") LocalDateTime now
    );
}
