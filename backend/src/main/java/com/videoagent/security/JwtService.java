package com.videoagent.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private static final int MINIMUM_SECRET_BYTES = 32;

    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
    }

    public String issue(AuthenticatedUser user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.expiration());
        return Jwts.builder()
            .subject(Long.toString(user.id()))
            .claim("userId", user.id())
            .claim("username", user.username())
            .issuedAt(Date.from(issuedAt))
            .expiration(Date.from(expiresAt))
            .signWith(signingKey())
            .compact();
    }

    public AuthenticatedUser parse(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(signingKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
        long userId = Long.parseLong(claims.getSubject());
        String username = claims.get("username", String.class);
        if (userId <= 0 || username == null || username.isBlank()) {
            throw new IllegalArgumentException("JWT identity claims are invalid");
        }
        return new AuthenticatedUser(userId, username);
    }

    public long expiresInSeconds() {
        return properties.expiration().toSeconds();
    }

    private SecretKey signingKey() {
        String secret = properties.secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET must be configured");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}
