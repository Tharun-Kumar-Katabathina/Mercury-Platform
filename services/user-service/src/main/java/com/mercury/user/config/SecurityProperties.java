package com.mercury.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/** mercury.security.*: account lockout and the bootstrap admin / service clients. */
@ConfigurationProperties(prefix = "mercury.security")
public record SecurityProperties(
        @DefaultValue("5") int maxFailedLogins,
        @DefaultValue("15m") Duration lockDuration,
        /** per client address, per minute */
        @DefaultValue("20") int loginAttemptsPerMinute,
        /** the first administrator, created at start-up if it does not exist. Both must be set to create one. */
        String adminEmail,
        String adminPassword,
        /** "name:secret,name2:secret2": the other Mercury services. Secrets are hashed in memory at start-up. */
        @DefaultValue List<String> serviceClients
) {
}
