package com.videoagent.upload.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoagent.upload.entity.VideoUploadPartEntity;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface VideoUploadPartRepository extends BaseMapper<VideoUploadPartEntity> {

    @Select("SELECT * FROM video_upload_part WHERE upload_id = #{uploadId} ORDER BY part_number ASC")
    List<VideoUploadPartEntity> findByUploadId(@Param("uploadId") String uploadId);

    @Select("SELECT * FROM video_upload_part WHERE upload_id = #{uploadId} AND part_number = #{partNumber} LIMIT 1")
    VideoUploadPartEntity findPart(@Param("uploadId") String uploadId, @Param("partNumber") int partNumber);

    @Insert("""
        INSERT INTO video_upload_part (
            upload_id, part_number, object_key, expected_size, actual_size,
            etag, checksum_sha256, status, completed_at, created_at, updated_at
        ) VALUES (
            #{uploadId}, #{partNumber}, #{objectKey}, #{expectedSize}, #{actualSize},
            #{etag}, #{checksumSha256}, 'COMPLETED', #{now}, #{now}, #{now}
        )
        ON DUPLICATE KEY UPDATE
            actual_size = VALUES(actual_size),
            etag = VALUES(etag),
            checksum_sha256 = COALESCE(VALUES(checksum_sha256), checksum_sha256),
            status = 'COMPLETED',
            completed_at = VALUES(completed_at),
            updated_at = VALUES(updated_at)
        """)
    int upsertCompleted(
        @Param("uploadId") String uploadId,
        @Param("partNumber") int partNumber,
        @Param("objectKey") String objectKey,
        @Param("expectedSize") long expectedSize,
        @Param("actualSize") long actualSize,
        @Param("etag") String etag,
        @Param("checksumSha256") String checksumSha256,
        @Param("now") LocalDateTime now
    );
}
