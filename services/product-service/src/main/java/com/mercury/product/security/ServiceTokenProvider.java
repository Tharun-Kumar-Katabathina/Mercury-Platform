package com.mercury.product.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * This service's own bearer token for calling other Mercury services: fetched from the user-service with the
 * service's client id and secret, cached, and renewed shortly before it expires. If a token cannot be obtained the
 * outgoing call is not made at all (the caller sees an ordinary "unavailable" failure).
 */
public class ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenProvider.class);
    private static final Duration RENEW_BEFORE_EXPIRY = Duration.ofSeconds(30);

    private final SecurityProperties.ServiceClient client;
    private final RestClient http;
    private volatile String token;
    private volatile Instant expiresAt = Instant.EPOCH;

    public ServiceTokenProvider(SecurityProperties.ServiceClient client) {
        this.client = client;
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public static class UnavailableException extends RuntimeException {
        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public String token() {
        if (token == null || Instant.now().isAfter(expiresAt.minus(RENEW_BEFORE_EXPIRY))) {
            refresh();
        }
        return token;
    }

    private synchronized void refresh() {
        if (token != null && Instant.now().isBefore(expiresAt.minus(RENEW_BEFORE_EXPIRY))) {
            return;                                  // another thread renewed it while this one waited
        }
        try {
            JsonNode body = http.post().uri(client.tokenUrl()).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("clientId", client.id(), "clientSecret", client.secret()))
                    .retrieve().body(JsonNode.class);
            token = body.path("accessToken").asString();
            expiresAt = Instant.now().plusSeconds(body.path("expiresIn").asLong(60));
            log.info("obtained a service token for {} (valid {}s)", client.id(), body.path("expiresIn").asLong());
        } catch (RuntimeException e) {
            throw new UnavailableException("cannot obtain a service token: " + e.getMessage(), e);
        }
    }
}
