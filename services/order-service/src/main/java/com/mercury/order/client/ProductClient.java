package com.mercury.order.client;

import com.mercury.order.dto.ProductDetails;
import com.mercury.order.exception.ProductServiceException;
import org.springframework.beans.factory.annotation.Qualifier;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProductClient.class);

    private final RestClient restClient;
    private final DownstreamGuard guard;

    public ProductClient(
            RestClient.Builder builder,
            @Value("${product.service.url}") String productServiceUrl,
            @Qualifier("productGuard") DownstreamGuard guard) {

        this.guard = guard;
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

        return guard.execute(() -> {
            try {
                return restClient.get()
                        .uri("/api/v1/products/{id}", productId)
                        .retrieve()
                        .body(ProductDetails.class);
            } catch (ResourceAccessException e) {
                log.warn("product lookup productId={} failed: {}", productId, e.getMessage());
                throw new ProductServiceException(HttpStatus.SERVICE_UNAVAILABLE, null, e);
            }
        }, rejection -> {
            log.warn("product lookup productId={} not attempted: {}", productId, rejection.toString());
            return new ProductServiceException(HttpStatus.SERVICE_UNAVAILABLE, null, rejection, true);
        });
    }

    private static String readBody(InputStream body) {
        try {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
