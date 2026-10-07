package com.mercury.gateway.ratelimit;

import com.mercury.gateway.config.GatewayProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * Two lines of defence against abuse. Registered twice: BEFORE authentication it limits by client address (so garbage
 * tokens and password guessing cannot be hammered for free), AFTER authentication it limits by user (so one customer
 * cannot starve the others from many addresses). The credential endpoints get a much stricter per-minute budget.
 */
public abstract class RateLimitFilter extends OncePerRequestFilter {

    public enum Scope { ADDRESS, USER }

    private final Scope scope;
    private final TokenBuckets buckets;
    private final TokenBuckets credentialBuckets;

    protected RateLimitFilter(Scope scope, GatewayProperties.RateLimit limits) {
        this.scope = scope;
        if (scope == Scope.ADDRESS) {
            this.buckets = new TokenBuckets(limits.ipPerSecond(), limits.ipBurst());
            this.credentialBuckets = new TokenBuckets(limits.authPerMinute() / 60d, Math.max(1, limits.authPerMinute()));
        } else {
            this.buckets = new TokenBuckets(limits.userPerSecond(), limits.userBurst());
            this.credentialBuckets = null;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String key = key(request);
        if (key == null) {
            chain.doFilter(request, response);
            return;
        }
        long wait = 0;
        if (scope == Scope.ADDRESS && credentialBuckets != null && isCredentialEndpoint(request)) {
            wait = credentialBuckets.tryConsume(key);
        }
        if (wait == 0) {
            wait = buckets.tryConsume(key);
        }
        if (wait > 0) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(wait));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"timestamp\":\"" + Instant.now() + "\",\"status\":429,\"error\":\"RATE_LIMITED\","
                    + "\"message\":\"Too many requests, retry in " + wait + "s\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private String key(HttpServletRequest request) {
        if (scope == Scope.ADDRESS) {
            return request.getRemoteAddr();
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName()) ? auth.getName() : null;
    }

    private static boolean isCredentialEndpoint(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "POST".equals(request.getMethod())
                && (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/register") || path.equals("/api/v1/auth/service-token"));
    }

    /**
     * Before authentication, keyed by client address. A distinct class from {@link User}: Spring Security orders its
     * filters by class, so two instances of one class would end up at the same position.
     */
    public static final class Address extends RateLimitFilter {
        public Address(GatewayProperties.RateLimit limits) {
            super(Scope.ADDRESS, limits);
        }
    }

    /** After authentication, keyed by the token's subject. */
    public static final class User extends RateLimitFilter {
        public User(GatewayProperties.RateLimit limits) {
            super(Scope.USER, limits);
        }
    }
}
