package com.mercury.recommendation.security;

import com.mercury.recommendation.Waiting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static com.mercury.recommendation.security.TestTokens.bearer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "mercury.security.enabled=true")
@AutoConfigureMockMvc
class SecurityApiTests {

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("mercury.security.jwt.public-key", TestTokens::trustedPublicKeyPem);
    }

    @Autowired private MockMvc mvc;
    @Autowired private ScheduledTaskHolder scheduler;
    @MockitoBean private com.mercury.recommendation.service.VectorIndex index;

    private final String product = UUID.randomUUID().toString();

    /**
     * The context's own first sync pass starts with the context and pushes whatever earlier tests left unindexed in the
     * shared database, through the mock above. Mockito keeps "the call that is being stubbed" per mock, not per thread: a
     * push that lands between the test's {@code index.similarTo(...)} and its {@code .thenReturn(...)} is the call that
     * {@code thenReturn} then checks the answer against, and fails with "'upsert' is a void method". The first test to run
     * is the one that stubs, right after the context has started, so no test starts before that pass is over.
     */
    @BeforeEach
    void afterTheStartupSync() {
        Waiting.untilTheStartupSyncIsOver(scheduler);
    }

    @Test
    void popularProductsArePublicEverythingElseNeedsALogin() throws Exception {
        mvc.perform(get("/api/v1/recommendations/popular")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/recommendations/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/recommendations/products/" + product + "/similar")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/interactions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"%s\",\"type\":\"VIEW\"}".formatted(product))).andExpect(status().isUnauthorized());
    }

    @Test
    void forgedTokensAreRefused() throws Exception {
        for (String bad : new String[]{"garbage", TestTokens.expired(), TestTokens.wrongIssuer(), TestTokens.wrongAudience(),
                TestTokens.signedByStranger(), TestTokens.unsigned()}) {
            mvc.perform(get("/api/v1/recommendations/me").header("Authorization", bearer(bad))).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void aCustomerGetsTheirOwnRecommendationsAndCanRecordViews() throws Exception {
        when(index.similarTo(any(), anyInt())).thenReturn(List.of());
        String alice = bearer(TestTokens.user("alice"));

        mvc.perform(get("/api/v1/recommendations/me").header("Authorization", alice)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/interactions").contentType(MediaType.APPLICATION_JSON).header("Authorization", alice)
                .content("{\"productId\":\"%s\",\"type\":\"VIEW\"}".formatted(product))).andExpect(status().isAccepted());
    }

    @Test
    void aClientCannotClaimAPurchase() throws Exception {
        mvc.perform(post("/api/v1/interactions").contentType(MediaType.APPLICATION_JSON).header("Authorization", bearer(TestTokens.user("alice")))
                .content("{\"productId\":\"%s\",\"type\":\"PURCHASE\"}".formatted(product))).andExpect(status().isBadRequest());
    }

    @Test
    void aServiceTokenIsNotACustomer() throws Exception {
        mvc.perform(get("/api/v1/recommendations/me").header("Authorization", bearer(TestTokens.service()))).andExpect(status().isForbidden());
    }

    @Test
    void malformedInputIsRefusedCleanly() throws Exception {
        String alice = bearer(TestTokens.user("alice"));
        mvc.perform(get("/api/v1/recommendations/products/not-a-uuid/similar").header("Authorization", alice)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/interactions").contentType(MediaType.APPLICATION_JSON).header("Authorization", alice)
                .content("{\"productId\":\"1' OR '1'='1\",\"type\":\"VIEW\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.trace").doesNotExist());
        mvc.perform(post("/api/v1/interactions").contentType(MediaType.APPLICATION_JSON).header("Authorization", alice)
                .content("{\"productId\":\"%s\",\"type\":\"DROP_TABLE\"}".formatted(product))).andExpect(status().isBadRequest());
    }
}
