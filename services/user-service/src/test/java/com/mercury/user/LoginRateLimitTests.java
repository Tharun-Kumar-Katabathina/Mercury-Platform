package com.mercury.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Password guessing from one address is throttled before any password is hashed.
 *
 * The limiter counts per wall-clock minute, so these tests control the clock: against the real clock the six attempts
 * of the first test straddle a minute boundary in roughly one run in thirty, the counter restarts, and the sixth attempt
 * is (correctly) not refused, which made the test fail at random.
 */
@SpringBootTest(properties = "mercury.security.login-attempts-per-minute=5")
@AutoConfigureMockMvc
@Import(LoginRateLimitTests.ControlledClock.class)
class LoginRateLimitTests {

    /** A clock that only moves when the test moves it. */
    static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong();

        void set(Instant instant) { millis.set(instant.toEpochMilli()); }
        void advanceMillis(long delta) { millis.addAndGet(delta); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public long millis() { return millis.get(); }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
    }

    @TestConfiguration
    static class ControlledClock {
        static final MutableClock CLOCK = new MutableClock();

        @Bean @Primary
        Clock controlledClock() { return CLOCK; }
    }

    @Autowired private MockMvc mvc;

    private int login() throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@mercury.test\",\"password\":\"whatever-it-is\"}"))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void theSixthAttemptWithinAMinuteIsRefusedWith429AndRetryAfter() throws Exception {
        ControlledClock.CLOCK.set(Instant.parse("2026-01-01T12:00:10Z"));
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"nobody@mercury.test\",\"password\":\"whatever-it-is\"}"))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@mercury.test\",\"password\":\"whatever-it-is\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
    }

    @Test
    void theCountRestartsWhenTheMinuteChanges() throws Exception {
        ControlledClock.CLOCK.set(Instant.parse("2026-01-01T13:00:59.900Z"));
        for (int i = 0; i < 5; i++) {
            org.assertj.core.api.Assertions.assertThat(login()).isEqualTo(401);
        }
        org.assertj.core.api.Assertions.assertThat(login()).as("same minute: over the limit").isEqualTo(429);

        ControlledClock.CLOCK.advanceMillis(200);                                // 13:01:00.100: a new window

        org.assertj.core.api.Assertions.assertThat(login()).as("next minute: a fresh allowance").isEqualTo(401);
    }
}
