package com.mercury.user.service;

import com.mercury.user.config.JwtKeys;
import com.mercury.user.config.JwtProperties;
import com.mercury.user.dto.TokenResponse;
import com.mercury.user.model.Role;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Signs access tokens (RS256). Short-lived by design: there is no refresh token and no server-side session. */
@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final JwtKeys keys;
    private final JwtProperties properties;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, JwtKeys keys, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.keys = keys;
        this.properties = properties;
        this.clock = clock;
    }

    public TokenResponse forUser(UUID userId, String email, Set<Role> roles) {
        return issue(userId.toString(), email, roles, properties.userTtl());
    }

    public TokenResponse forService(String clientId) {
        return issue("service:" + clientId, null, Set.of(Role.SERVICE), properties.serviceTtl());
    }

    private TokenResponse issue(String subject, String email, Set<Role> roles, Duration ttl) {
        Instant now = Instant.now(clock);
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(subject)
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(now.plus(ttl))
                .id(UUID.randomUUID().toString())
                .claim("roles", roles.stream().map(Enum::name).sorted().toList());
        if (email != null) {
            claims.claim("email", email);
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.signingKey().getKeyID()).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        return TokenResponse.bearer(token, ttl.toSeconds());
    }
}
