package com.mercury.order.security;

import com.mercury.order.client.InventoryClient;
import com.mercury.order.client.ProductClient;
import com.mercury.order.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.util.UUID;

import static com.mercury.order.security.TestTokens.bearer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * What a customer is told when something fails inside the service.
 *
 * Found by the failure matrix (F08, PostgreSQL restarted under load): a customer holding a valid token got
 * {@code 403 Your role may not do this} for some orders. Two defects were stacked: the failed rollback of a transaction
 * on a connection the database had just closed escaped every exception handler, and the container's internal error
 * dispatch ({@code /error}) was then authorised like any other request and refused by {@code denyAll()}, replacing the real
 * status with a security rejection. These tests run against a real HTTP port because MockMvc never performs an error dispatch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"mercury.security.enabled=true", "mercury.security.service-client.secret=test-secret"})
class FailureResponseStatusTests {

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("mercury.security.jwt.public-key", TestTokens::trustedPublicKeyPem);
    }

    @Value("${local.server.port}") private int port;
    @MockitoBean private OrderService orderService;
    @MockitoBean private ProductClient productClient;
    @MockitoBean private InventoryClient inventoryClient;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.header("Authorization", bearer(TestTokens.user("alice"))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> placeOrder() throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/orders"))
                .header("Content-Type", "application/json").header("Idempotency-Key", "key-" + UUID.randomUUID())
                .POST(HttpRequest.BodyPublishers.ofString("{\"items\":[{\"productId\":\"%s\",\"quantity\":1}]}".formatted(UUID.randomUUID()))));
    }

    @Test
    void anAllowedPathWithNoHandlerIsNotFoundForAnAdministratorNotForbidden() throws Exception {
        // authorised (administrators may use /actuator/**) but nothing is mapped there: the container answers 404 through its error dispatch
        var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/nothing-here"))
                .header("Authorization", bearer(TestTokens.admin())).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
    }

    @Test
    void aPathThatIsNotListedIsStillRefusedForACustomer() throws Exception {
        var response = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/orders/a/b/c")).GET());

        assertThat(response.statusCode()).as("anything not listed is denied, as documented").isEqualTo(403);
    }

    @Test
    void anUnexpectedFailureIsAServerErrorNotAnAuthorisationFailure() throws Exception {
        when(orderService.createOrder(any(), any(), any())).thenThrow(new IllegalStateException("a bug"));

        var response = placeOrder();

        assertThat(response.statusCode()).as(response.body()).isEqualTo(500);
        assertThat(response.body()).doesNotContain("FORBIDDEN").doesNotContain("a bug");     // and no internals are revealed
    }

    @Test
    void aRollbackThatFailsBecauseTheDatabaseClosedTheConnectionIsADatabaseOutage() throws Exception {
        when(orderService.createOrder(any(), any(), any())).thenThrow(new JpaSystemException(
                new RuntimeException("Unable to rollback against JDBC Connection", new SQLException("Connection is closed"))));

        var response = placeOrder();

        assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
        assertThat(response.body()).contains("DATABASE_UNAVAILABLE");
        assertThat(response.headers().firstValue("Retry-After")).contains("5");
    }

    @Test
    void aPersistenceFailureThatIsNotAConnectionProblemStaysAServerError() throws Exception {
        when(orderService.createOrder(any(), any(), any())).thenThrow(new JpaSystemException(
                new RuntimeException("could not execute statement", new SQLException("duplicate key value", "23505"))));

        var response = placeOrder();

        assertThat(response.statusCode()).as(response.body()).isEqualTo(500);      // a bug is not an outage: it must not hide as a 503
    }
}
