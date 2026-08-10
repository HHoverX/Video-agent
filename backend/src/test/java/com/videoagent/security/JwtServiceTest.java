package com.videoagent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.ExpiredJwtException;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class JwtServiceTest {

    private static final String SECRET = "unit-test-jwt-secret-with-more-than-32-bytes";

    @Test
    void shouldIssueAndVerifyIdentityClaims() {
        JwtService service = new JwtService(new JwtProperties(SECRET, Duration.ofHours(2)));

        String token = service.issue(new AuthenticatedUser(7L, "alice"));

        assertThat(service.parse(token)).isEqualTo(new AuthenticatedUser(7L, "alice"));
        assertThat(service.expiresInSeconds()).isEqualTo(7_200L);
    }

    @Test
    void shouldRejectExpiredToken() {
        JwtService service = new JwtService(new JwtProperties(SECRET, Duration.ofSeconds(-1)));
        String token = service.issue(new AuthenticatedUser(7L, "alice"));

        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void shouldRequireConfiguredSecretWithoutExposingItsValue() {
        JwtService service = new JwtService(new JwtProperties("", Duration.ofHours(2)));

        assertThatThrownBy(() -> service.issue(new AuthenticatedUser(7L, "alice")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("JWT_SECRET must be configured");
    }
}
