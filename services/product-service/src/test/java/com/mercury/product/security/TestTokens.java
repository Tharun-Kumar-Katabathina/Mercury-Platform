package com.mercury.product.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Mints tokens for the security tests, exactly as the user-service would (and deliberately wrong ones). */
public final class TestTokens {

    public static final KeyPair TRUSTED = newPair();
    public static final KeyPair FOREIGN = newPair();

    private TestTokens() {
    }

    private static KeyPair newPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** the PEM the services are configured with (mercury.security.jwt.public-key) */
    public static String trustedPublicKeyPem() {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(TRUSTED.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }

    public static String user(String subject)  { return sign(TRUSTED, subject, List.of("USER"), "mercury-user-service", "mercury", 600); }
    public static String admin()               { return sign(TRUSTED, "admin-1", List.of("ADMIN", "USER"), "mercury-user-service", "mercury", 600); }
    public static String service()             { return sign(TRUSTED, "service:order-service", List.of("SERVICE"), "mercury-user-service", "mercury", 600); }
    public static String expired()             { return sign(TRUSTED, "u-1", List.of("USER"), "mercury-user-service", "mercury", -3600); }
    public static String wrongIssuer()         { return sign(TRUSTED, "u-1", List.of("USER"), "someone-else", "mercury", 600); }
    public static String wrongAudience()       { return sign(TRUSTED, "u-1", List.of("USER"), "mercury-user-service", "another-app", 600); }
    public static String signedByStranger()    { return sign(FOREIGN, "u-1", List.of("ADMIN"), "mercury-user-service", "mercury", 600); }

    /** a token whose signature part is removed: the "alg=none"-style forgery */
    public static String unsigned() {
        String valid = admin();
        return valid.substring(0, valid.lastIndexOf('.') + 1);
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String sign(KeyPair pair, String subject, List<String> roles, String issuer, String audience, long ttlSeconds) {
        try {
            long now = System.currentTimeMillis();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer).audience(audience).subject(subject).jwtID(UUID.randomUUID().toString())
                    .issueTime(new Date(now - 5_000)).expirationTime(new Date(now + ttlSeconds * 1000))
                    .claim("roles", roles).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) pair.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
