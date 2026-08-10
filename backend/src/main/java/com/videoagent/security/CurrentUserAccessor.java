package com.videoagent.security;

import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserAccessor {

    public AuthenticatedUser require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
            || !authentication.isAuthenticated()
            || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new VideoAgentException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    public long userId() {
        return require().id();
    }
}
