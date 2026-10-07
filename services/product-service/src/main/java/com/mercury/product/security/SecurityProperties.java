package com.mercury.product.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** mercury.security.*: whether requests must be authenticated, and how tokens are verified. */
@ConfigurationProperties(prefix = "mercury.security")
public record SecurityProperties(
        /** true (the default) in every real deployment; the unit tests of the business logic switch it off */
        @DefaultValue("true") boolean enabled,
        Jwt jwt,
        ServiceClient serviceClient
) {

    public record Jwt(
            @DefaultValue("mercury-user-service") String issuer,
            @DefaultValue("mercury") String audience,
            /** PEM (X.509) public key of the user-service signing key, inline or as a mounted file */
            String publicKey,
            String publicKeyFile
    ) {
    }

    /** This service's own identity towards other services (the user-service issues the SERVICE token). */
    public record ServiceClient(String id, String secret, String tokenUrl) {
    }
}
