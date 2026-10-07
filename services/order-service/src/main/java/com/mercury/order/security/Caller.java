package com.mercury.order.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is calling, as far as order ownership goes: the customer id (the token subject) and whether the caller may see
 * every order (administrators and other services). With security switched off every caller is anonymous and privileged.
 */
public record Caller(String customerId, boolean privileged) {

    public static final Caller ANONYMOUS = new Caller(null, true);

    public static Caller from(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return ANONYMOUS;
        }
        boolean privileged = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_SERVICE"));
        return new Caller(jwt.getSubject(), privileged);
    }
}
