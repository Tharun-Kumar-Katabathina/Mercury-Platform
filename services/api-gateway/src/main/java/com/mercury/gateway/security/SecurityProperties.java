package com.mercury.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** mercury.security.*: the same token verification settings every Mercury service uses. */
@ConfigurationProperties(prefix = "mercury.security")
public record SecurityProperties(
        @DefaultValue("true") boolean enabled,
        Jwt jwt
) {

    public record Jwt(
            @DefaultValue("mercury-user-service") String issuer,
            @DefaultValue("mercury") String audience,
            String publicKey,
            String publicKeyFile
    ) {
    }
}
