package com.videoagent.auth.dto;

import com.videoagent.auth.entity.AppUserEntity;
import com.videoagent.security.AuthenticatedUser;

public record AuthUserResponse(long id, String username) {

    public static AuthUserResponse from(AppUserEntity user) {
        return new AuthUserResponse(user.getId(), user.getUsername());
    }

    public static AuthUserResponse from(AuthenticatedUser user) {
        return new AuthUserResponse(user.id(), user.username());
    }
}
