package com.mercury.gateway.config;

import com.mercury.gateway.ratelimit.RateLimitFilter;
import com.mercury.gateway.security.JwtSupport;
import com.mercury.gateway.security.SecurityProperties;
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
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.session.DisableEncodeUrlFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.time.Instant;

/**
 * The edge. It verifies the token before anything is forwarded (so an unauthenticated flood never reaches a service),
 * applies the rate limits, CORS, security headers and the body-size cap, and forwards only the public API: the
 * Inventory Service has no route here at all.
 */
@Configuration
@EnableConfigurationProperties({SecurityProperties.class, GatewayProperties.class})
public class SecurityConfiguration {

    @Bean
    @ConditionalOnProperty(name = "mercury.security.enabled", havingValue = "true", matchIfMissing = true)
    JwtDecoder jwtDecoder(SecurityProperties properties) {
        return JwtSupport.decoder(properties);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewayProperties properties) {
        GatewayProperties.Cors cors = properties.cors();
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(cors.allowedOrigins());            // empty list: no origin is allowed
        configuration.setAllowedMethods(cors.allowedMethods());
        configuration.setAllowedHeaders(cors.allowedHeaders());
        configuration.setExposedHeaders(cors.exposedHeaders());
        configuration.setAllowCredentials(false);                          // bearer tokens, never cookies
        configuration.setMaxAge(cors.maxAgeSeconds());
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    SecurityFilterChain gatewayFilterChain(
            HttpSecurity http, SecurityProperties security, GatewayProperties gateway, ObjectProvider<JwtDecoder> decoder)
            throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(c -> { })                                              // uses the CorsConfigurationSource bean
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .frameOptions(f -> f.deny())
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)))
                .addFilterBefore(new RequestSizeFilter(gateway.maxBodySize().toBytes()), DisableEncodeUrlFilter.class);

        if (gateway.rateLimit().enabled()) {
            http.addFilterBefore(new RateLimitFilter.Address(gateway.rateLimit()), BearerTokenAuthenticationFilter.class)
                    .addFilterAfter(new RateLimitFilter.User(gateway.rateLimit()), BearerTokenAuthenticationFilter.class);
        }

        if (!security.enabled()) {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }

        http
                .authorizeHttpRequests(a -> a
                        // the container's internal error dispatch keeps the real status (404 for a path with no route)
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()               // CORS pre-flight
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/products", "/api/v1/products/*").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers("/api/v1/auth/service-token").denyAll()                // internal: never through the edge
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.decoder(decoder.getObject()).jwtAuthenticationConverter(JwtSupport.converter()))
                        .authenticationEntryPoint((req, res, e) -> reply(res, 401, "UNAUTHORIZED", "A valid access token is required")))
                .exceptionHandling(x -> x
                        .authenticationEntryPoint((req, res, e) -> reply(res, 401, "UNAUTHORIZED", "A valid access token is required"))
                        .accessDeniedHandler((req, res, e) -> reply(res, 403, "FORBIDDEN", "This is not available here")));
        return http.build();
    }

    private static void reply(jakarta.servlet.http.HttpServletResponse res, int status, String error, String message) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (status == 401) {
            res.setHeader("WWW-Authenticate", "Bearer");
        }
        res.getWriter().write("{\"timestamp\":\"" + Instant.now() + "\",\"status\":" + status
                + ",\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
    }
}
