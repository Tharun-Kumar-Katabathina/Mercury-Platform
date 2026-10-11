package com.mercury.inventory.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.mercury.inventory.security.TestTokens.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Inventory is an internal service: stock is visible to administrators and services, reservations are services' business. */
@SpringBootTest(properties = "mercury.security.enabled=true")
@AutoConfigureMockMvc
class SecurityApiTests {

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("mercury.security.jwt.public-key", TestTokens::trustedPublicKeyPem);
    }

    @Autowired private MockMvc mvc;

    private static final String PRODUCT = "6f1c2b8e-4f0a-4c53-9a53-1d2f3a4b5c6d";

    @Test
    void nothingIsReadableWithoutAToken() throws Exception {
        mvc.perform(get("/api/v1/inventory/" + PRODUCT)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        mvc.perform(post("/api/v1/inventory/" + PRODUCT + "/reserve").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "k").content("{\"quantity\":1}")).andExpect(status().isUnauthorized());
    }

    @Test
    void forgedExpiredAndMisaddressedTokensAreRefused() throws Exception {
        for (String bad : new String[]{"garbage", TestTokens.expired(), TestTokens.wrongIssuer(), TestTokens.wrongAudience(),
                TestTokens.signedByStranger(), TestTokens.unsigned()}) {
            mvc.perform(get("/api/v1/inventory/" + PRODUCT).header("Authorization", bearer(bad))).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void aCustomerCannotSeeOrTouchStock() throws Exception {
        String user = bearer(TestTokens.user("u-1"));
        mvc.perform(get("/api/v1/inventory/" + PRODUCT).header("Authorization", user)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/inventory/" + PRODUCT + "/reserve").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "k").content("{\"quantity\":1}").header("Authorization", user)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/inventory/" + PRODUCT + "/release").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "k").content("{\"quantity\":1}").header("Authorization", user)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/inventory").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"%s\",\"availableQuantity\":999999}".formatted(PRODUCT)).header("Authorization", user)).andExpect(status().isForbidden());
    }

    @Test
    void fencingAReservationIsForServicesOnly() throws Exception {
        String fence = "/api/v1/inventory/" + PRODUCT + "/reservations/k/fence";
        mvc.perform(post(fence)).andExpect(status().isUnauthorized());
        mvc.perform(post(fence).header("Authorization", bearer(TestTokens.user("u-1")))).andExpect(status().isForbidden());
        mvc.perform(post(fence).header("Authorization", bearer(TestTokens.service()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FENCED"));
    }

    @Test
    void aServiceMayReserveAndReadButNotCreateOrSetStock() throws Exception {
        String service = bearer(TestTokens.service());
        mvc.perform(get("/api/v1/inventory/" + UUID.randomUUID()).header("Authorization", service)).andExpect(status().isNotFound());     // allowed, no such record
        mvc.perform(post("/api/v1/inventory/" + UUID.randomUUID() + "/reserve").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "k").content("{\"quantity\":1}").header("Authorization", service)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/inventory").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"%s\",\"availableQuantity\":5}".formatted(UUID.randomUUID())).header("Authorization", service)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/inventory/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"availableQuantity\":5,\"version\":0}").header("Authorization", service)).andExpect(status().isForbidden());
    }

    @Test
    void anAdministratorCanCreateStock() throws Exception {
        mvc.perform(post("/api/v1/inventory").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"%s\",\"availableQuantity\":5}".formatted(UUID.randomUUID()))
                        .header("Authorization", bearer(TestTokens.admin())))
                .andExpect(status().isCreated());
    }

    @Test
    void operationalEndpointsNeedAPrivilegedRole() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/outbox")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/outbox").header("Authorization", bearer(TestTokens.user("u-1")))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/outbox").header("Authorization", bearer(TestTokens.service()))).andExpect(status().isOk());
    }

    @Test
    void malformedInputIsRejectedWithoutLeakingInternals() throws Exception {
        String admin = bearer(TestTokens.admin());
        mvc.perform(get("/api/v1/inventory/not-a-uuid").header("Authorization", admin)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/inventory/" + PRODUCT + "/reserve").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "x'; DROP TABLE inventory; --").content("{\"quantity\":-5}")
                .header("Authorization", admin)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.trace").doesNotExist());
    }
}
