package com.mercury.user.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/** The RSA key pair that signs access tokens. Loaded from configuration; generated only when explicitly allowed. */
public final class JwtKeys {

    private static final Logger log = LoggerFactory.getLogger(JwtKeys.class);

    private final RSAKey key;

    private JwtKeys(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        this.key = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(thumbprint(publicKey)).build();
    }

    public static JwtKeys load(JwtProperties properties) {
        try {
            String privatePem = read(properties.privateKey(), properties.privateKeyFile());
            String publicPem = read(properties.publicKey(), properties.publicKeyFile());
            if (privatePem != null && publicPem != null) {
                return new JwtKeys(parsePublic(publicPem), parsePrivate(privatePem));
            }
            if (properties.requireKeys()) {
                throw new IllegalStateException(
                        "JWT signing keys are not configured (mercury.security.jwt.private-key[-file] and public-key[-file]). "
                                + "Generate a pair with scripts/generate-jwt-keys.sh");
            }
            log.warn("No JWT keys configured: generating a throw-away key pair. Tokens will not survive a restart "
                    + "and other services cannot verify them. Never do this outside development.");
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new JwtKeys((RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("JWT signing keys could not be loaded: " + e.getMessage(), e);
        }
    }

    public RSAKey signingKey() {
        return key;
    }

    /** the signing key, for the encoder */
    public JWKSet publicJwkSetWithPrivate() {
        return new JWKSet(key);
    }

    public JWKSet publicJwkSet() {
        return new JWKSet(key.toPublicJWK());
    }

    private static String read(String inline, String file) throws Exception {
        if (file != null && !file.isBlank()) {
            return Files.readString(Path.of(file), StandardCharsets.UTF_8);
        }
        return inline == null || inline.isBlank() ? null : inline;
    }

    private static byte[] der(String pem) {
        String body = pem.replaceAll("-----(BEGIN|END)[A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static RSAPrivateKey parsePrivate(String pem) throws Exception {
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der(pem)));
    }

    private static RSAPublicKey parsePublic(String pem) throws Exception {
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der(pem)));
    }

    private static String thumbprint(RSAPublicKey key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded())).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
