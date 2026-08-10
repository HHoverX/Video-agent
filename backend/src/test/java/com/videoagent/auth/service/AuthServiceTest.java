package com.videoagent.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videoagent.auth.dto.LoginRequest;
import com.videoagent.auth.dto.LoginResponse;
import com.videoagent.auth.dto.RegisterRequest;
import com.videoagent.auth.entity.AppUserEntity;
import com.videoagent.auth.repository.AppUserRepository;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.security.JwtService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {

    private final AppUserRepository repository = mock(AppUserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JwtService jwtService = mock(JwtService.class);
    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(repository, passwordEncoder, jwtService);
    }

    @Test
    void shouldRegisterWithBcryptHashAndNeverPersistPlaintext() {
        when(repository.findByUsername("alice")).thenReturn(null);
        when(repository.insert(any(AppUserEntity.class))).thenAnswer(invocation -> {
            AppUserEntity user = invocation.getArgument(0);
            user.setId(7L);
            return 1;
        });

        assertThat(service.register(new RegisterRequest(" alice ", "safe-password")).username())
            .isEqualTo("alice");

        ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().getPasswordHash())
            .startsWith("$2")
            .isNotEqualTo("safe-password");
        assertThat(passwordEncoder.matches("safe-password", captor.getValue().getPasswordHash()))
            .isTrue();
    }

    @Test
    void shouldRejectDuplicateUsername() {
        when(repository.findByUsername("alice")).thenReturn(user("alice", "hash"));

        assertThatThrownBy(() ->
            service.register(new RegisterRequest("alice", "safe-password"))
        ).isInstanceOfSatisfying(VideoAgentException.class, exception ->
            assertThat(exception.errorCode()).isEqualTo(ErrorCode.USERNAME_ALREADY_EXISTS)
        );
    }

    @Test
    void shouldLoginWithValidPasswordAndReturnJwtMetadata() {
        AppUserEntity user = user("alice", passwordEncoder.encode("safe-password"));
        when(repository.findByUsername("alice")).thenReturn(user);
        when(jwtService.issue(any())).thenReturn("signed.jwt.token");
        when(jwtService.expiresInSeconds()).thenReturn(7_200L);

        LoginResponse response = service.login(new LoginRequest("alice", "safe-password"));

        assertThat(response.token()).isEqualTo("signed.jwt.token");
        assertThat(response.expiresIn()).isEqualTo(7_200L);
        assertThat(response.user().id()).isEqualTo(7L);
    }

    @Test
    void shouldReturnGenericUnauthorizedErrorForInvalidPassword() {
        AppUserEntity user = user("alice", passwordEncoder.encode("safe-password"));
        when(repository.findByUsername("alice")).thenReturn(user);

        assertThatThrownBy(() -> service.login(new LoginRequest("alice", "wrong-password")))
            .isInstanceOfSatisfying(VideoAgentException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS)
            );
    }

    private AppUserEntity user(String username, String hash) {
        AppUserEntity user = new AppUserEntity();
        user.setId(7L);
        user.setUsername(username);
        user.setPasswordHash(hash);
        return user;
    }
}
