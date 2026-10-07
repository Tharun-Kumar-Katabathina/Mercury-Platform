package com.mercury.recommendation.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import java.time.Instant;
import java.util.Map;

/**
 * Who may call what. Every request needs a valid, unexpired token from the user-service (RS256, issuer and audience
 * checked) unless it is listed as public, and the role in the token decides the rest. Anything not listed is denied.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {

    @Bean
    @ConditionalOnProperty(name = "mercury.security.enabled", havingValue = "true", matchIfMissing = true)
    JwtDecoder jwtDecoder(SecurityProperties properties) {
        return JwtSupport.decoder(properties);
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, SecurityProperties properties, ObjectProvider<JwtDecoder> decoder) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)                       // stateless bearer-token API, no cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .frameOptions(f -> f.deny())
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)));

        if (!properties.enabled()) {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }

        http
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/v1/interactions").hasAnyRole("USER", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/recommendations/popular").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/recommendations/products/*/similar", "/api/v1/recommendations/products/*/bought-together").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/recommendations/me").hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").hasAnyRole("ADMIN", "SERVICE")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.decoder(decoder.getObject()).jwtAuthenticationConverter(JwtSupport.converter()))
                        .authenticationEntryPoint((req, res, e) -> reply(res, 401, "UNAUTHORIZED", "A valid access token is required"))
                        .accessDeniedHandler((req, res, e) -> reply(res, 403, "FORBIDDEN", "Your role may not do this")))
                .exceptionHandling(x -> x
                        .authenticationEntryPoint((req, res, e) -> reply(res, 401, "UNAUTHORIZED", "A valid access token is required"))
                        .accessDeniedHandler((req, res, e) -> reply(res, 403, "FORBIDDEN", "Your role may not do this")));
        return http.build();
    }

    private static void reply(jakarta.servlet.http.HttpServletResponse res, int status, String error, String message) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (status == 401) {
            res.setHeader("WWW-Authenticate", "Bearer");
        }
        res.getWriter().write("{\"timestamp\":\"" + Instant.now() + "\",\"status\":" + status
                + ",\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
    }
}
