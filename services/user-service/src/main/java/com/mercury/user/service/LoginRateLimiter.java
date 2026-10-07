package com.mercury.user.service;

import com.mercury.user.config.SecurityProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A fixed one-minute window per client key (address): the cheap first line against password guessing and
 * credential stuffing, applied before any password is hashed (bcrypt is deliberately expensive, so unlimited
 * attempts would also be a CPU attack). Per instance; the account lockout is the shared, database-backed line.
 */
@Component
public class LoginRateLimiter {

    private record Window(long minute, int count) { }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int limit;
    private final Clock clock;

    public LoginRateLimiter(SecurityProperties properties, Clock clock) {
        this.limit = properties.loginAttemptsPerMinute();
        this.clock = clock;
    }

    /** @return false when this client is over its limit for the current minute */
    public boolean tryAcquire(String clientKey) {
        long minute = clock.millis() / 60_000;
        Window w = windows.merge(clientKey, new Window(minute, 1),
                (old, fresh) -> old.minute() == minute ? new Window(minute, old.count() + 1) : fresh);
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> e.getValue().minute() < minute);   // bounded memory
        }
        return w.count() <= limit;
    }
}
