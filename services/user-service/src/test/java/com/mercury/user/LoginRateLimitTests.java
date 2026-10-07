package com.mercury.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Password guessing from one address is throttled before any password is hashed. */
@SpringBootTest(properties = "mercury.security.login-attempts-per-minute=5")
@AutoConfigureMockMvc
class LoginRateLimitTests {

    @Autowired private MockMvc mvc;

    @Test
    void theSixthAttemptWithinAMinuteIsRefusedWith429AndRetryAfter() throws Exception {
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
}
