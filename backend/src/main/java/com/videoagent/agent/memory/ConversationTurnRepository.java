package com.videoagent.agent.memory;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ConversationTurnRepository extends BaseMapper<ConversationTurnEntity> {

    @Select("""
        SELECT *
        FROM conversation_turn
        WHERE user_id = #{userId}
          AND video_id = #{videoId}
        ORDER BY created_at DESC, id DESC
        LIMIT #{limit}
        """)
    List<ConversationTurnEntity> findRecent(
        @Param("userId") long userId,
        @Param("videoId") long videoId,
        @Param("limit") int limit
    );

    @Select("""
        SELECT COUNT(*)
        FROM conversation_turn
        WHERE user_id = #{userId}
          AND video_id = #{videoId}
        """)
    long countByUserIdAndVideoId(
        @Param("userId") long userId,
        @Param("videoId") long videoId
    );
}
