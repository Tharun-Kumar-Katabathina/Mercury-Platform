package com.mercury.gateway;

import com.mercury.gateway.ratelimit.TokenBuckets;
import com.mercury.gateway.security.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicLong;

import static com.mercury.gateway.security.TestTokens.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Abuse protection: per address before authentication, per user after it, and a much tighter budget for credentials. */
@SpringBootTest(properties = {
        "mercury.security.enabled=true",
        "gateway.rate-limit.ip-per-second=0.001", "gateway.rate-limit.ip-burst=1000",
        "gateway.rate-limit.user-per-second=0.001", "gateway.rate-limit.user-burst=3",
        "gateway.rate-limit.auth-per-minute=3"})
@AutoConfigureMockMvc
class RateLimitTests {

    static final StubDownstream DOWNSTREAM = new StubDownstream();

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("mercury.security.jwt.public-key", TestTokens::trustedPublicKeyPem);
        for (String name : new String[]{"USER", "PRODUCT", "ORDER", "NOTIFICATION"}) {
            registry.add(name + "_SERVICE_URL", DOWNSTREAM::url);
        }
    }

    @AfterAll
    static void stop() {
        DOWNSTREAM.close();
    }

    @Autowired private MockMvc mvc;

    @Test
    void passwordGuessingIsCutOffAfterTheCredentialBudget() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        }

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"));
    }

    @Test
    void oneUserCannotExceedTheirOwnBudgetButAnotherUserIsUnaffected() throws Exception {
        String alice = bearer(TestTokens.user("alice"));
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/orders/1").header("Authorization", alice)).andExpect(status().isOk());
        }

        mvc.perform(get("/api/v1/orders/1").header("Authorization", alice)).andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/v1/orders/1").header("Authorization", bearer(TestTokens.user("bob")))).andExpect(status().isOk());
    }

    @Test
    void aBucketRefillsAtItsRateAndAnIdleOneIsForgotten() {
        AtomicLong clock = new AtomicLong();
        TokenBuckets buckets = new TokenBuckets(2, 2, clock::get, 10);        // burst of 2, then 2 per second

        assertThat(buckets.tryConsume("k")).isZero();
        assertThat(buckets.tryConsume("k")).isZero();
        assertThat(buckets.tryConsume("k")).isPositive();                      // empty
        clock.addAndGet(600_000_000L);                                         // 0.6 s: 1.2 tokens
        assertThat(buckets.tryConsume("k")).isZero();
        assertThat(buckets.tryConsume("k")).isPositive();

        for (int i = 0; i < 20; i++) {                                         // more keys than the bound ...
            buckets.tryConsume("other-" + i);
        }
        clock.addAndGet(10_000_000_000L);                                      // ... and a long idle time
        buckets.tryConsume("trigger");
        assertThat(buckets.size()).isLessThan(5);                              // idle buckets were dropped
    }
}
