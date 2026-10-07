package com.mercury.order.security;

import com.mercury.order.client.InventoryClient;
import com.mercury.order.client.ProductClient;
import com.mercury.order.dto.InventoryOperationResult;
import com.mercury.order.dto.ProductDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static com.mercury.order.security.TestTokens.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Order Service with authentication ON: who may order, who may see which order, and keys that belong to a customer. */
@SpringBootTest(properties = {"mercury.security.enabled=true", "mercury.security.service-client.secret=test-secret",
        "management.endpoints.web.exposure.include=health,metrics,sagas,outbox"})
@AutoConfigureMockMvc
class SecurityApiTests {

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("mercury.security.jwt.public-key", TestTokens::trustedPublicKeyPem);
    }

    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @MockitoBean private ProductClient productClient;
    @MockitoBean private InventoryClient inventoryClient;

    private UUID productId;

    @BeforeEach
    void aProductAndAcceptingInventory() {
        productId = UUID.randomUUID();
        when(productClient.getProduct(productId)).thenReturn(new ProductDetails(productId, "Item", "SKU-" + productId, new BigDecimal("10.00")));
        when(inventoryClient.reserve(any(), anyInt(), anyString())).thenReturn(new InventoryOperationResult(false));
    }

    private MvcResult place(String token, String key, int quantity) throws Exception {
        var request = post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"productId\":\"%s\",\"quantity\":%d}]}".formatted(productId, quantity));
        if (token != null) request.header("Authorization", bearer(token));
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request).andReturn();
    }

    private UUID idOf(MvcResult r) throws Exception {
        return UUID.fromString(json.readTree(r.getResponse().getContentAsString()).path("id").asString());
    }

    @Test
    void orderingNeedsALogin() throws Exception {
        assertThat(place(null, "k1", 1).getResponse().getStatus()).isEqualTo(401);
        for (String bad : new String[]{"garbage", TestTokens.expired(), TestTokens.wrongIssuer(), TestTokens.wrongAudience(),
                TestTokens.signedByStranger(), TestTokens.unsigned()}) {
            assertThat(place(bad, "k1", 1).getResponse().getStatus()).as("token %s", bad.substring(0, Math.min(12, bad.length()))).isEqualTo(401);
        }
    }

    @Test
    void aServiceTokenCannotPlaceCustomerOrders() throws Exception {
        assertThat(place(TestTokens.service(), "k1", 1).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void aCustomerCanOrderAndReadTheirOwnOrder() throws Exception {
        String alice = TestTokens.user("alice");
        MvcResult placed = place(alice, "alice-1", 2);
        assertThat(placed.getResponse().getStatus()).isEqualTo(201);

        mvc.perform(get("/api/v1/orders/" + idOf(placed)).header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void anotherCustomerGetsNotFoundExactlyLikeAnOrderThatDoesNotExist() throws Exception {
        UUID aliceOrder = idOf(place(TestTokens.user("alice"), "alice-2", 1));
        String bob = bearer(TestTokens.user("bob"));

        MvcResult foreign = mvc.perform(get("/api/v1/orders/" + aliceOrder).header("Authorization", bob)).andReturn();
        MvcResult missing = mvc.perform(get("/api/v1/orders/" + UUID.randomUUID()).header("Authorization", bob)).andReturn();

        assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(json.readTree(foreign.getResponse().getContentAsString()).path("error").asString())
                .isEqualTo(json.readTree(missing.getResponse().getContentAsString()).path("error").asString());
    }

    @Test
    void administratorsAndServicesCanReadAnyOrder() throws Exception {
        UUID aliceOrder = idOf(place(TestTokens.user("alice"), "alice-3", 1));

        mvc.perform(get("/api/v1/orders/" + aliceOrder).header("Authorization", bearer(TestTokens.admin()))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/orders/" + aliceOrder).header("Authorization", bearer(TestTokens.service()))).andExpect(status().isOk());
    }

    @Test
    void theSameIdempotencyKeyFromTwoCustomersMakesTwoOrdersAndNeverLeaksOne() throws Exception {
        MvcResult alice = place(TestTokens.user("alice"), "shared-key", 1);
        MvcResult bob = place(TestTokens.user("bob"), "shared-key", 1);

        assertThat(alice.getResponse().getStatus()).isEqualTo(201);
        assertThat(bob.getResponse().getStatus()).isEqualTo(201);
        assertThat(bob.getResponse().getHeader("Idempotent-Replayed")).isNull();      // not a replay of Alice's order
        assertThat(idOf(bob)).isNotEqualTo(idOf(alice));
    }

    @Test
    void theSameCustomerReplayingTheirKeyStillGetsTheSameOrder() throws Exception {
        String carol = TestTokens.user("carol");
        MvcResult first = place(carol, "carol-1", 1);
        MvcResult again = place(carol, "carol-1", 1);

        assertThat(idOf(again)).isEqualTo(idOf(first));
        assertThat(again.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
    }

    @Test
    void malformedAndHostileInputIsRefusedCleanly() throws Exception {
        String alice = bearer(TestTokens.user("alice"));
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).header("Authorization", alice)
                .header("Idempotency-Key", "k").content("{ nope")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).header("Authorization", alice)
                .header("Idempotency-Key", "x'; DROP TABLE orders; --")
                .content("{\"items\":[{\"productId\":\"1' OR '1'='1\",\"quantity\":1}]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).header("Authorization", alice)
                .header("Idempotency-Key", "k").content("{\"items\":[{\"productId\":\"%s\",\"quantity\":-3}]}".formatted(productId)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.trace").doesNotExist());
        mvc.perform(get("/api/v1/orders/not-a-uuid").header("Authorization", alice)).andExpect(status().isBadRequest());
    }

    @Test
    void operationalEndpointsAreNotForCustomers() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/sagas")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/sagas").header("Authorization", bearer(TestTokens.user("alice")))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/sagas").header("Authorization", bearer(TestTokens.admin()))).andExpect(status().isOk());
    }
}
