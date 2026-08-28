package com.videoagent.video.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoagent.video.entity.VideoEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface VideoRepository extends BaseMapper<VideoEntity> {

    @Insert("""
        INSERT INTO video (
            user_id, title, original_filename, object_key, file_size,
            mime_type, file_hash, status, created_at, updated_at
        ) VALUES (
            #{userId}, #{title}, #{originalFilename}, #{objectKey}, #{fileSize},
            #{mimeType}, #{fileHash}, #{status}, #{createdAt}, #{updatedAt}
        )
        ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertOrReuseByUserAndFileHash(VideoEntity video);

    @Select("""
        SELECT * FROM video
        WHERE user_id = #{userId}
          AND file_hash = #{fileHash}
        LIMIT 1 FOR UPDATE
        """)
    VideoEntity findByUserIdAndFileHash(
        @Param("userId") long userId,
        @Param("fileHash") String fileHash
    );

}
