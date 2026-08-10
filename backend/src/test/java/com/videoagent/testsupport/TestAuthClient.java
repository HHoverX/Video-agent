package com.videoagent.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.videoagent.auth.dto.AuthUserResponse;
import com.videoagent.auth.dto.LoginResponse;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

public final class TestAuthClient {

    public static final String JWT_SECRET = "integration-test-jwt-secret-with-more-than-32-bytes";
    private static final String PASSWORD = "integration-password";

    private TestAuthClient() {
    }

    public static Session registerAndLogin(TestRestTemplate restTemplate, String baseUrl, String username) {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<AuthUserResponse> registered = restTemplate.postForEntity(
            baseUrl + "/api/auth/register",
            new HttpEntity<>(Map.of("username", username, "password", PASSWORD), json),
            AuthUserResponse.class
        );
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registered.getBody()).isNotNull();

        ResponseEntity<LoginResponse> login = restTemplate.postForEntity(
            baseUrl + "/api/auth/login",
            new HttpEntity<>(Map.of("username", username, "password", PASSWORD), json),
            LoginResponse.class
        );
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).isNotNull();
        return new Session(registered.getBody().id(), login.getBody().token());
    }

    public record Session(long userId, String token) {

        public HttpHeaders headers() {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            return headers;
        }
    }
}
