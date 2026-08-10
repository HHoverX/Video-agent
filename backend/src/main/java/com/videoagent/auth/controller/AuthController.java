package com.videoagent.auth.controller;

import com.videoagent.auth.dto.AuthUserResponse;
import com.videoagent.auth.dto.LoginRequest;
import com.videoagent.auth.dto.LoginResponse;
import com.videoagent.auth.dto.RegisterRequest;
import com.videoagent.auth.service.AuthService;
import com.videoagent.security.CurrentUserAccessor;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final CurrentUserAccessor currentUser;

    public AuthController(AuthService authService, CurrentUserAccessor currentUser) {
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthUserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public AuthUserResponse me() {
        return AuthUserResponse.from(currentUser.require());
    }
}
