package com.videoagent.auth.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.videoagent.auth.entity.AppUserEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AppUserRepository extends BaseMapper<AppUserEntity> {

    @Select("SELECT * FROM app_user WHERE username = #{username} LIMIT 1")
    AppUserEntity findByUsername(@Param("username") String username);
}
