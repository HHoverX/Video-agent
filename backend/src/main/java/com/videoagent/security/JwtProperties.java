package com.videoagent.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "videoagent.security.jwt")
public record JwtProperties(String secret, Duration expiration) {

    public JwtProperties {
        expiration = expiration == null ? Duration.ofHours(2) : expiration;
    }
}
