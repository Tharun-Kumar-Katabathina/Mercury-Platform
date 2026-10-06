package com.mercury.order.client;

import com.mercury.order.dto.ProductDetails;
import com.mercury.order.exception.ProductServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * HTTP client for Product Service, the authority on name, SKU and price. Order Service never
 * reads Product's database. Every failure leaves this class as a ProductServiceException.
 */
@Component
public class ProductClient {

    private final RestClient restClient;

    public ProductClient(
            RestClient.Builder builder,
            @Value("${product.service.url}") String productServiceUrl) {

        this.restClient = builder
                .baseUrl(productServiceUrl)
                .defaultStatusHandler(
                        status -> status.isError(),
                        (request, response) -> {
                            throw new ProductServiceException(
                                    response.getStatusCode(), readBody(response.getBody()));
                        })
                .build();
    }

    public ProductDetails getProduct(UUID productId) {

        try {
            return restClient.get()
                    .uri("/api/v1/products/{id}", productId)
                    .retrieve()
                    .body(ProductDetails.class);
        } catch (ResourceAccessException e) {
            throw new ProductServiceException(HttpStatus.SERVICE_UNAVAILABLE, null, e);
        }
    }

    private static String readBody(InputStream body) {
        try {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
