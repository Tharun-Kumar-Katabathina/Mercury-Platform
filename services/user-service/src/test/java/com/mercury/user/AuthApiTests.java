package com.mercury.user;

import com.mercury.user.config.JwtKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Registration, login, lockout, service tokens and the shape of the tokens that come out. */
@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTests {

    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper json;
    @Autowired private JwtDecoder decoder;
    @Autowired private JwtKeys keys;

    private static String email() {
        return "user-" + UUID.randomUUID() + "@mercury.test";
    }

    private void register(String email, String password) throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isCreated());
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))).andReturn();
    }

    private String tokenFor(String email, String password) throws Exception {
        MvcResult r = login(email, password);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString()).path("accessToken").asString();
    }

    @Test
    void registeringCreatesAUserAccountAndNeverReturnsTheHash() throws Exception {
        String email = email();
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"a-long-enough-password\"}".formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.roles[0]").value("USER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void theSameEmailCannotRegisterTwiceEvenWithDifferentCase() throws Exception {
        String email = email();
        register(email, "a-long-enough-password");

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"another-long-password\"}".formatted(email.toUpperCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void weakOrMalformedRegistrationsAreRefusedWithAMessageAndNoInternals() throws Exception {
        for (String body : List.of(
                "{\"email\":\"not-an-email\",\"password\":\"a-long-enough-password\"}",
                "{\"email\":\"" + email() + "\",\"password\":\"short\"}",
                "{\"email\":\"" + email() + "\",\"password\":\"" + "x".repeat(73) + "\"}",
                "{\"email\":\"\",\"password\":\"\"}",
                "{ this is not json")) {
            mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.stackTrace").doesNotExist())
                    .andExpect(jsonPath("$.trace").doesNotExist());
        }
    }

    @Test
    void aLoginReturnsASignedShortLivedTokenWithTheRightClaims() throws Exception {
        String email = email();
        register(email, "a-long-enough-password");

        MvcResult result = login(email, "a-long-enough-password");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("tokenType").asString()).isEqualTo("Bearer");
        assertThat(body.path("expiresIn").asLong()).isEqualTo(900);
        Jwt jwt = decoder.decode(body.path("accessToken").asString());
        assertThat(jwt.<String>getClaim("iss")).isEqualTo("mercury-user-service");
        assertThat(jwt.getAudience()).containsExactly("mercury");
        assertThat(jwt.<List<String>>getClaim("roles")).containsExactly("USER");
        assertThat(jwt.<String>getClaim("email")).isEqualTo(email);
        assertThat(jwt.getExpiresAt()).isBefore(Instant.now().plusSeconds(901));
        assertThat(jwt.getHeaders().get("kid")).isEqualTo(keys.signingKey().getKeyID());
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("RS256");
    }

    @Test
    void wrongPasswordAndUnknownAccountGiveTheIdenticalAnswer() throws Exception {
        String email = email();
        register(email, "a-long-enough-password");

        MvcResult wrong = login(email, "totally-wrong-password");
        MvcResult unknown = login(email(), "totally-wrong-password");

        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(json.readTree(wrong.getResponse().getContentAsString()).path("message").asString())
                .isEqualTo(json.readTree(unknown.getResponse().getContentAsString()).path("message").asString());
    }

    @Test
    void repeatedWrongPasswordsLockTheAccountEvenAgainstTheRightPassword() throws Exception {
        String email = email();
        register(email, "a-long-enough-password");
        for (int i = 0; i < 3; i++) {
            assertThat(login(email, "wrong-password-" + i).getResponse().getStatus()).isEqualTo(401);
        }

        MvcResult withRightPassword = login(email, "a-long-enough-password");

        assertThat(withRightPassword.getResponse().getStatus()).isEqualTo(401);   // locked, and indistinguishable from "wrong"
    }

    @Test
    void aServiceExchangesItsSecretForAServiceToken() throws Exception {
        MvcResult ok = mvc.perform(post("/api/v1/auth/service-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"order-service\",\"clientSecret\":\"service-secret-1\"}")).andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        Jwt jwt = decoder.decode(json.readTree(ok.getResponse().getContentAsString()).path("accessToken").asString());
        assertThat(jwt.getSubject()).isEqualTo("service:order-service");
        assertThat(jwt.<List<String>>getClaim("roles")).containsExactly("SERVICE");
    }

    @Test
    void aWrongServiceSecretOrAnotherServicesSecretIsRefused() throws Exception {
        for (String body : List.of(
                "{\"clientId\":\"order-service\",\"clientSecret\":\"wrong\"}",
                "{\"clientId\":\"order-service\",\"clientSecret\":\"service-secret-2\"}",     // product's secret
                "{\"clientId\":\"nobody\",\"clientSecret\":\"service-secret-1\"}")) {
            mvc.perform(post("/api/v1/auth/service-token").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void theBootstrapAdministratorExistsWithAdminAndUserRoles() throws Exception {
        String token = tokenFor("root@mercury.test", "correct-horse-battery");

        assertThat(decoder.decode(token).<List<String>>getClaim("roles")).containsExactlyInAnyOrder("ADMIN", "USER");
    }

    @Test
    void theProfileEndpointNeedsAValidToken() throws Exception {
        String email = email();
        register(email, "a-long-enough-password");
        String token = tokenFor(email, "a-long-enough-password");

        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer garbage")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void theJwksEndpointPublishesOnlyThePublicKey() throws Exception {
        mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].kid").value(keys.signingKey().getKeyID()))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist())
                .andExpect(jsonPath("$.keys[0].p").doesNotExist());
    }

    @Test
    void responsesCarrySecurityHeaders() throws Exception {
        mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }
}
