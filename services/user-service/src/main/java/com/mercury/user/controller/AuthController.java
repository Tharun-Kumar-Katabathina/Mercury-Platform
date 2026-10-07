package com.mercury.user.controller;

import com.mercury.user.dto.LoginRequest;
import com.mercury.user.dto.RegisterRequest;
import com.mercury.user.dto.ServiceTokenRequest;
import com.mercury.user.dto.TokenResponse;
import com.mercury.user.dto.UserResponse;
import com.mercury.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(auth.register(request));
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return auth.login(request, client(http));
    }

    /** For other Mercury services: exchanges their client id and secret for a short-lived SERVICE token. */
    @PostMapping("/service-token")
    public TokenResponse serviceToken(@Valid @RequestBody ServiceTokenRequest request, HttpServletRequest http) {
        return auth.serviceToken(request, client(http));
    }

    private static String client(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
