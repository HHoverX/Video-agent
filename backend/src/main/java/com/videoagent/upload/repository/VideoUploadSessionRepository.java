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
        WHERE user_id = #{userId}
          AND active_expected_sha256 = #{expectedSha256}
          AND expires_at > #{now}
        LIMIT 1
        FOR UPDATE
        """)
    VideoUploadSessionEntity findReusableByHash(
        @Param("userId") long userId,
        @Param("expectedSha256") String expectedSha256,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE video_upload_session
        SET status = 'EXPIRED', updated_at = #{now}
        WHERE user_id = #{userId}
          AND expected_sha256 = #{expectedSha256}
          AND status IN ('CREATED', 'UPLOADING', 'FAILED')
          AND expires_at <= #{now}
        """)
    int expireReusableByHash(
        @Param("userId") long userId,
        @Param("expectedSha256") String expectedSha256,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE video_upload_session
        SET status = 'COMPLETING', completing_at = #{completingAt},
            completion_token = #{completionToken}, expected_sha256 = #{expectedSha256},
            last_error = NULL, updated_at = #{completingAt}
        WHERE id = #{uploadId}
          AND status = #{previousStatus}
        """)
    int markCompletionStarted(
        @Param("uploadId") String uploadId,
        @Param("previousStatus") String previousStatus,
        @Param("completionToken") String completionToken,
        @Param("completingAt") LocalDateTime completingAt,
        @Param("expectedSha256") String expectedSha256
    );

    @Select("""
        SELECT * FROM video_upload_session
        WHERE status = 'COMPLETING'
          AND completing_at < #{cutoff}
        ORDER BY completing_at ASC
        LIMIT #{limit}
        """)
    List<VideoUploadSessionEntity> findTimedOutCompletions(
        @Param("cutoff") LocalDateTime cutoff,
        @Param("limit") int limit
    );

    @Update("""
        UPDATE video_upload_session
        SET status = 'COMPLETED', video_id = #{videoId}, completed_at = #{now},
            completing_at = NULL, completion_token = NULL, last_error = NULL, updated_at = #{now}
        WHERE id = #{uploadId}
          AND status = 'COMPLETING'
          AND completion_token = #{completionToken}
        """)
    int markCompletionCompleted(
        @Param("uploadId") String uploadId,
        @Param("completionToken") String completionToken,
        @Param("videoId") long videoId,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE video_upload_session
        SET status = 'FAILED', completing_at = NULL, completion_token = NULL,
            last_error = #{message}, updated_at = #{now}
        WHERE id = #{uploadId}
          AND status = 'COMPLETING'
          AND completion_token = #{completionToken}
        """)
    int markCompletionFailed(
        @Param("uploadId") String uploadId,
        @Param("completionToken") String completionToken,
        @Param("message") String message,
        @Param("now") LocalDateTime now
    );

    @Update("""
        UPDATE video_upload_session
        SET status = 'UPLOADING', last_error = NULL, updated_at = #{now}
        WHERE id = #{uploadId}
          AND status IN ('CREATED', 'FAILED')
        """)
    int markUploading(@Param("uploadId") String uploadId, @Param("now") LocalDateTime now);

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
