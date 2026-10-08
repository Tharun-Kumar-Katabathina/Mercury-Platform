package com.mercury.gateway;

import com.mercury.gateway.security.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.mercury.gateway.security.TestTokens.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Authentication, routing, CORS, headers and size limits at the edge, with a stub in place of every downstream service. */
@SpringBootTest(properties = {
        "mercury.security.enabled=true",
        "gateway.cors.allowed-origins=https://shop.example.com",
        "gateway.max-body-size=200B",
        "gateway.rate-limit.ip-per-second=1000", "gateway.rate-limit.ip-burst=1000",
        "gateway.rate-limit.user-per-second=1000", "gateway.rate-limit.user-burst=1000",
        "gateway.rate-limit.auth-per-minute=1000"})
@AutoConfigureMockMvc
class GatewayEdgeTests {

    static final StubDownstream DOWNSTREAM = new StubDownstream();

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("mercury.security.jwt.public-key", TestTokens::trustedPublicKeyPem);
        for (String name : new String[]{"USER", "PRODUCT", "ORDER", "NOTIFICATION", "RECOMMENDATION"}) {
            registry.add(name + "_SERVICE_URL", DOWNSTREAM::url);
        }
    }

    @AfterAll
    static void stop() {
        DOWNSTREAM.close();
    }

    @Autowired private MockMvc mvc;

    @BeforeEach
    void forgetEarlierCalls() {
        DOWNSTREAM.calls.clear();
    }

    @Test
    void anUnauthenticatedCallNeverReachesAService() throws Exception {
        mvc.perform(get("/api/v1/orders/" + java.util.UUID.randomUUID()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        assertThat(DOWNSTREAM.calls).isEmpty();
    }

    @Test
    void forgedExpiredAndMisaddressedTokensNeverReachAService() throws Exception {
        for (String bad : new String[]{"garbage", TestTokens.expired(), TestTokens.wrongIssuer(), TestTokens.wrongAudience(),
                TestTokens.signedByStranger(), TestTokens.unsigned()}) {
            mvc.perform(get("/api/v1/orders/x").header("Authorization", bearer(bad))).andExpect(status().isUnauthorized());
        }

        assertThat(DOWNSTREAM.calls).isEmpty();
    }

    @Test
    void theCatalogueAndLoginArePublicAndAreForwarded() throws Exception {
        mvc.perform(get("/api/v1/products/42")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@b.c\",\"password\":\"x\"}"))
                .andExpect(status().isOk());

        assertThat(DOWNSTREAM.calls).extracting(StubDownstream.Call::path).containsExactly("/api/v1/products/42", "/api/v1/auth/login");
    }

    @Test
    void popularRecommendationsArePublicButPersonalOnesNeedAToken() throws Exception {
        mvc.perform(get("/api/v1/recommendations/popular?limit=5")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/recommendations/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/recommendations/products/42/similar")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/recommendations/me").header("Authorization", bearer(TestTokens.user("alice")))).andExpect(status().isOk());

        assertThat(DOWNSTREAM.calls).extracting(StubDownstream.Call::path)
                .containsExactly("/api/v1/recommendations/popular", "/api/v1/recommendations/me");
    }

    @Test
    void recordingAnInteractionNeedsATokenAndIsForwardedToTheRecommendationService() throws Exception {
        String body = "{\"productId\":\"42\",\"type\":\"VIEW\"}";

        mvc.perform(post("/api/v1/interactions").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/interactions").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", bearer(TestTokens.user("alice")))).andExpect(status().isOk());

        assertThat(DOWNSTREAM.calls).extracting(StubDownstream.Call::path).containsExactly("/api/v1/interactions");
    }

    @Test
    void anAuthenticatedCallIsForwardedWithItsTokenIntact() throws Exception {
        String token = TestTokens.user("alice");

        mvc.perform(get("/api/v1/orders/123").header("Authorization", bearer(token))).andExpect(status().isOk());

        assertThat(DOWNSTREAM.calls).singleElement().satisfies(c -> {
            assertThat(c.path()).isEqualTo("/api/v1/orders/123");
            assertThat(c.authorization()).isEqualTo(bearer(token));     // the service verifies it again: defence in depth
        });
    }

    @Test
    void inventoryIsInternalAndHasNoRouteEvenForAnAdministrator() throws Exception {
        mvc.perform(get("/api/v1/inventory/1").header("Authorization", bearer(TestTokens.admin()))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/inventory/1")).andExpect(status().isUnauthorized());

        assertThat(DOWNSTREAM.calls).isEmpty();
    }

    @Test
    void theServiceTokenEndpointIsNotReachableFromOutside() throws Exception {
        mvc.perform(post("/api/v1/auth/service-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"order-service\",\"clientSecret\":\"x\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/service-token").contentType(MediaType.APPLICATION_JSON).header("Authorization", bearer(TestTokens.admin()))
                .content("{\"clientId\":\"order-service\",\"clientSecret\":\"x\"}")).andExpect(status().isForbidden());

        assertThat(DOWNSTREAM.calls).isEmpty();
    }

    @Test
    void anAllowedBrowserOriginGetsCorsHeadersAndAnotherDoesNot() throws Exception {
        mvc.perform(options("/api/v1/orders").header("Origin", "https://shop.example.com")
                        .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "authorization,idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://shop.example.com"));

        mvc.perform(options("/api/v1/orders").header("Origin", "https://evil.example.net")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anOversizedBodyIsRefusedBeforeItIsForwarded() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).header("Authorization", bearer(TestTokens.user("alice")))
                        .content("{\"items\":[" + "\"x\",".repeat(200) + "\"x\"]}"))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.error").value("PAYLOAD_TOO_LARGE"));

        assertThat(DOWNSTREAM.calls).isEmpty();
    }

    @Test
    void everyResponseCarriesSecurityHeaders() throws Exception {
        mvc.perform(get("/api/v1/products/1"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void healthIsOpenForTheOrchestratorButMetricsDetailsAreNot() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }
}
