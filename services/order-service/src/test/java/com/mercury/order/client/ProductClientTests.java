package com.mercury.order.client;

import com.mercury.order.dto.ProductDetails;
import com.mercury.order.exception.ProductServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ProductClientTests {

    private static final String BASE_URL = "http://product.test";

    private final UUID productId = UUID.randomUUID();
    private MockRestServiceServer server;
    private ProductClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ProductClient(builder, BASE_URL);
    }

    @Test
    void readsNameSkuAndPriceAndIgnoresEverythingElse() {
        server.expect(requestTo(BASE_URL + "/api/v1/products/" + productId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"%s","name":"MacBook Pro 16","sku":"MBP-16-M4","price":2499.00,
                         "quantity":10,"createdAt":"2026-10-06T06:37:35.948962Z",
                         "updatedAt":"2026-10-06T06:37:35.948962Z"}
                        """.formatted(productId), MediaType.APPLICATION_JSON));

        ProductDetails product = client.getProduct(productId);

        assertThat(product).isEqualTo(new ProductDetails(
                productId, "MacBook Pro 16", "MBP-16-M4", new BigDecimal("2499.00")));
        server.verify();
    }

    @Test
    void productNotFoundKeepsStatusAndBody() {
        server.expect(requestTo(BASE_URL + "/api/v1/products/" + productId))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"PRODUCT_NOT_FOUND\",\"status\":404}"));

        assertThatThrownBy(() -> client.getProduct(productId))
                .isInstanceOfSatisfying(ProductServiceException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(404);
                    assertThat(e.getResponseBody()).contains("PRODUCT_NOT_FOUND");
                });
    }

    @Test
    void serverErrorBecomesProductServiceException() {
        server.expect(requestTo(BASE_URL + "/api/v1/products/" + productId))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.getProduct(productId))
                .isInstanceOfSatisfying(ProductServiceException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(500));
    }

    @Test
    void unreachableProductServiceBecomes503() {
        server.expect(requestTo(BASE_URL + "/api/v1/products/" + productId))
                .andRespond(request -> {
                    throw new IOException("connection refused");
                });

        assertThatThrownBy(() -> client.getProduct(productId))
                .isInstanceOfSatisfying(ProductServiceException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(503));
    }
}
