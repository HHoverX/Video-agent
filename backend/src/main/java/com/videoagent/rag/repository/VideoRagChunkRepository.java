package com.videoagent.rag.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoagent.rag.entity.VideoRagChunkEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface VideoRagChunkRepository extends BaseMapper<VideoRagChunkEntity> {

    @Delete("DELETE FROM video_rag_chunk WHERE user_id = #{userId} AND video_id = #{videoId}")
    int deleteByUserAndVideo(@Param("userId") long userId, @Param("videoId") long videoId);

    @Select("""
        SELECT id, chunk_id, user_id, video_id, analysis_task_id, chunk_index, text,
               start_ms, end_ms, source_segment_indexes, created_at,
               MATCH(text) AGAINST(#{query} IN NATURAL LANGUAGE MODE) AS lexicalScore
        FROM video_rag_chunk
        WHERE user_id = #{userId}
          AND video_id = #{videoId}
          AND MATCH(text) AGAINST(#{query} IN NATURAL LANGUAGE MODE)
        ORDER BY lexicalScore DESC, chunk_index ASC
        LIMIT #{limit}
        """)
    List<VideoRagChunkEntity> search(
        @Param("userId") long userId,
        @Param("videoId") long videoId,
        @Param("query") String query,
        @Param("limit") int limit
    );
}
