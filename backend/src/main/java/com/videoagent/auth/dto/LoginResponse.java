package com.videoagent.auth.dto;

public record LoginResponse(
    String token,
    long expiresIn,
    AuthUserResponse user
) {
}
