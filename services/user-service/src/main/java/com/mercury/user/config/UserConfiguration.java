package com.mercury.user.config;

import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({JwtProperties.class, SecurityProperties.class})
public class UserConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public JwtKeys jwtKeys(JwtProperties properties) {
        return JwtKeys.load(properties);
    }

    @Bean
    public JwtEncoder jwtEncoder(JwtKeys keys) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(keys.publicJwkSetWithPrivate()));
    }

    /** verifies the tokens this service issued (used by /users/me) */
    @Bean
    public JwtDecoder jwtDecoder(JwtKeys keys) throws Exception {
        return NimbusJwtDecoder.withPublicKey(keys.signingKey().toRSAPublicKey()).build();
    }
}
