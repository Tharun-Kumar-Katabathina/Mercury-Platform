package com.mercury.user.controller;

import com.mercury.user.dto.UserResponse;
import com.mercury.user.service.AuthService;
import com.nimbusds.jose.jwk.JWKSet;
import com.mercury.user.config.JwtKeys;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class UserController {

    private final AuthService auth;
    private final JwtKeys keys;

    public UserController(AuthService auth, JwtKeys keys) {
        this.auth = auth;
        this.keys = keys;
    }

    @GetMapping("/api/v1/users/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return auth.profile(jwt.getSubject());
    }

    /** The public half of the signing key, for services that prefer fetching it to being configured with it. */
    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        JWKSet set = keys.publicJwkSet();
        return set.toJSONObject();
    }
}
