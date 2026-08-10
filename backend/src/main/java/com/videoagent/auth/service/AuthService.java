package com.videoagent.auth.service;

import com.videoagent.auth.dto.AuthUserResponse;
import com.videoagent.auth.dto.LoginRequest;
import com.videoagent.auth.dto.LoginResponse;
import com.videoagent.auth.dto.RegisterRequest;
import com.videoagent.auth.entity.AppUserEntity;
import com.videoagent.auth.repository.AppUserRepository;
import com.videoagent.common.exception.ErrorCode;
import com.videoagent.common.exception.VideoAgentException;
import com.videoagent.security.AuthenticatedUser;
import com.videoagent.security.JwtService;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Service
public class AuthService {

    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
        AppUserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthUserResponse register(RegisterRequest request) {
        String username = normalizedUsername(request.username());
        validatePasswordBytes(request.password());
        if (userRepository.findByUsername(username) != null) {
            throw new VideoAgentException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }

        LocalDateTime now = LocalDateTime.now();
        AppUserEntity user = new AppUserEntity();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        try {
            if (userRepository.insert(user) != 1 || user.getId() == null) {
                throw new IllegalStateException("User insert did not return an id");
            }
        } catch (DuplicateKeyException exception) {
            throw new VideoAgentException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
        return AuthUserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String username = normalizedUsername(request.username());
        validatePasswordBytes(request.password());
        AppUserEntity user = userRepository.findByUsername(username);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new VideoAgentException(ErrorCode.INVALID_CREDENTIALS);
        }

        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getUsername());
        return new LoginResponse(
            jwtService.issue(principal),
            jwtService.expiresInSeconds(),
            AuthUserResponse.from(user)
        );
    }

    private String normalizedUsername(String username) {
        return username.strip();
    }

    private void validatePasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES) {
            throw new VideoAgentException(
                ErrorCode.VALIDATION_ERROR,
                "密码的 UTF-8 编码长度不能超过 72 字节"
            );
        }
    }
}
