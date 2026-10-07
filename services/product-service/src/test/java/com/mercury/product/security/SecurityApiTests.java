package com.mercury.product.security;

import com.mercury.product.client.InventoryClient;
import com.mercury.product.dto.InventoryReservationResponse;
import com.mercury.product.dto.InventoryReservationResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static com.mercury.product.security.TestTokens.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Product Service with authentication ON: who may read, who may change the catalogue, and what a forged token gets. */
@SpringBootTest(properties = {"mercury.security.enabled=true", "mercury.security.service-client.secret=test-secret"})
@AutoConfigureMockMvc
class SecurityApiTests {

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("mercury.security.jwt.public-key", TestTokens::trustedPublicKeyPem);
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private InventoryClient inventoryClient;

    private static final String NEW_PRODUCT = "{\"name\":\"Widget\",\"sku\":\"%s\",\"price\":9.99,\"quantity\":5}";

    private MockHttpServletRequestBuilder create(String token, String sku) {
        MockHttpServletRequestBuilder r = post("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                .content(NEW_PRODUCT.formatted(sku));
        return token == null ? r : r.header("Authorization", bearer(token));
    }

    @Test
    void theCatalogueCanBeReadWithoutLoggingIn() throws Exception {
        mvc.perform(get("/api/v1/products")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/products/" + UUID.randomUUID())).andExpect(status().isNotFound());   // reached the app: not 401
    }

    @Test
    void changingTheCatalogueWithoutAnyTokenIsRefusedWithAJsonError() throws Exception {
        mvc.perform(create(null, "S-" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void everyKindOfBadTokenIsRefused() throws Exception {
        for (String bad : new String[]{"garbage", TestTokens.expired(), TestTokens.wrongIssuer(), TestTokens.wrongAudience(),
                TestTokens.signedByStranger(), TestTokens.unsigned()}) {
            mvc.perform(create(bad, "S-" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void anOrdinaryUserCannotChangeTheCatalogue() throws Exception {
        String user = TestTokens.user("u-1");
        mvc.perform(create(user, "S-" + UUID.randomUUID())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        mvc.perform(put("/api/v1/products/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"price\":1,\"quantity\":1}").header("Authorization", bearer(user))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/products/" + UUID.randomUUID()).header("Authorization", bearer(user))).andExpect(status().isForbidden());
    }

    @Test
    void anAdministratorCanCreateAProduct() throws Exception {
        mvc.perform(create(TestTokens.admin(), "S-" + UUID.randomUUID())).andExpect(status().isCreated());
    }

    @Test
    void aServiceTokenCannotEditTheCatalogue() throws Exception {
        mvc.perform(create(TestTokens.service(), "S-" + UUID.randomUUID())).andExpect(status().isForbidden());
    }

    @Test
    void reservingNeedsAnyAuthenticatedRoleButNotNone() throws Exception {
        when(inventoryClient.reserveInventory(any(), anyInt(), anyString()))
                .thenReturn(new InventoryReservationResult(new InventoryReservationResponse(UUID.randomUUID(), 1, 9, 1, 1L), false));
        String url = "/api/v1/products/" + UUID.randomUUID() + "/reserve";

        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "k").content("{\"quantity\":1}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "k").content("{\"quantity\":1}")
                .header("Authorization", bearer(TestTokens.user("u-1")))).andExpect(status().isNotFound());   // past security: no such product
    }

    @Test
    void healthIsOpenButOperationalEndpointsAreNot() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").header("Authorization", bearer(TestTokens.user("u-1")))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics").header("Authorization", bearer(TestTokens.admin()))).andExpect(status().isOk());
    }

    @Test
    void anythingNotListedIsDeniedEvenToAnAdministrator() throws Exception {
        mvc.perform(get("/api/v1/secret-admin-thing").header("Authorization", bearer(TestTokens.admin()))).andExpect(status().isForbidden());
    }

    @Test
    void injectionAttemptsAreStoredAsPlainTextAndTheTableSurvives() throws Exception {
        long before = jdbc.queryForObject("select count(*) from products", Long.class);
        String evil = "x'); DROP TABLE products; --";

        mvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON).header("Authorization", bearer(TestTokens.admin()))
                        .content("{\"name\":\"%s\",\"sku\":\"%s\",\"price\":1,\"quantity\":1}".formatted(evil.replace("'", "\\u0027"), "SQLI-" + UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(evil));      // returned exactly as sent: it was data, never code

        assertThat(jdbc.queryForObject("select count(*) from products", Long.class)).isEqualTo(before + 1);
    }

    @Test
    void malformedPathsAndBodiesNeverLeakInternals() throws Exception {
        mvc.perform(get("/api/v1/products/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON).header("Authorization", bearer(TestTokens.admin()))
                .content("{ not json")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.trace").doesNotExist());
    }
}
