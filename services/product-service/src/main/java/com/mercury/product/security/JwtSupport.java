package com.mercury.product.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/** Builds the JWT decoder (signature, issuer, audience, expiry) and maps the `roles` claim to ROLE_* authorities. */
public final class JwtSupport {

    private JwtSupport() {
    }

    public static JwtDecoder decoder(SecurityProperties properties) {
        SecurityProperties.Jwt jwt = properties.jwt();
        if (jwt == null) {
            throw new IllegalStateException("mercury.security.jwt.* is required when security is enabled");
        }
        String pem = pem(jwt);
        if (pem == null) {
            throw new IllegalStateException(
                    "mercury.security.jwt.public-key[-file] is required when security is enabled "
                            + "(or set SECURITY_ENABLED=false for local development)");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey(pem)).build();
        OAuth2TokenValidator<Jwt> audience = token -> token.getAudience() != null && token.getAudience().contains(jwt.audience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "wrong audience", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(jwt.issuer()), audience));
        return decoder;
    }

    public static JwtAuthenticationConverter converter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(JwtSupport::authorities);
        return converter;
    }

    private static Collection<GrantedAuthority> authorities(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles == null ? List.of() : roles.stream()
                .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
    }

    private static String pem(SecurityProperties.Jwt jwt) {
        try {
            if (jwt.publicKeyFile() != null && !jwt.publicKeyFile().isBlank()) {
                return Files.readString(Path.of(jwt.publicKeyFile()), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot read the JWT public key file: " + e.getMessage(), e);
        }
        return jwt.publicKey() == null || jwt.publicKey().isBlank() ? null : jwt.publicKey();
    }

    private static RSAPublicKey publicKey(String pem) {
        try {
            byte[] der = Base64.getDecoder().decode(pem.replaceAll("-----(BEGIN|END)[A-Z ]+-----", "").replaceAll("\\s", ""));
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("the JWT public key is not a valid X.509 RSA key: " + e.getMessage(), e);
        }
    }
}
