package com.mercury.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** mercury.security.jwt.*: how access tokens are signed and how long they live. */
@ConfigurationProperties(prefix = "mercury.security.jwt")
public record JwtProperties(
        @DefaultValue("mercury-user-service") String issuer,
        @DefaultValue("mercury") String audience,
        @DefaultValue("15m") Duration userTtl,
        @DefaultValue("10m") Duration serviceTtl,
        /** PEM (PKCS#8) private key, inline. Prefer the file variant: it can come from a mounted secret. */
        String privateKey,
        String privateKeyFile,
        String publicKey,
        String publicKeyFile,
        /** true: refuse to start without a configured key pair (production). false: generate a throw-away pair (dev). */
        @DefaultValue("true") boolean requireKeys
) {
}
