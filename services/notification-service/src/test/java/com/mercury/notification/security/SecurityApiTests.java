package com.mercury.notification.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.mercury.notification.security.TestTokens.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Notifications can name customers and orders: administrators and services read them, nobody else. */
@SpringBootTest(properties = "mercury.security.enabled=true")
@AutoConfigureMockMvc
class SecurityApiTests {

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("mercury.security.jwt.public-key", TestTokens::trustedPublicKeyPem);
    }

    @Autowired private MockMvc mvc;

    private final String url = "/api/v1/notifications/orders/" + UUID.randomUUID();

    @Test
    void anonymousAndForgedCallersAreRefused() throws Exception {
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        for (String bad : new String[]{"garbage", TestTokens.expired(), TestTokens.wrongIssuer(), TestTokens.wrongAudience(),
                TestTokens.signedByStranger(), TestTokens.unsigned()}) {
            mvc.perform(get(url).header("Authorization", bearer(bad))).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void aCustomerCannotReadNotifications() throws Exception {
        mvc.perform(get(url).header("Authorization", bearer(TestTokens.user("u-1")))).andExpect(status().isForbidden());
    }

    @Test
    void administratorsAndServicesCan() throws Exception {
        mvc.perform(get(url).header("Authorization", bearer(TestTokens.admin()))).andExpect(status().isOk());
        mvc.perform(get(url).header("Authorization", bearer(TestTokens.service()))).andExpect(status().isOk());
    }

    @Test
    void thereIsNoWayToWriteAndAnythingElseIsDenied() throws Exception {
        mvc.perform(post("/api/v1/notifications").header("Authorization", bearer(TestTokens.admin()))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/everything").header("Authorization", bearer(TestTokens.admin()))).andExpect(status().isForbidden());
    }

    @Test
    void healthStaysOpenForTheOrchestrator() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
