package com.mercury.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.util.List;

/** gateway.*: the edge policies (rate limits, CORS, request size). */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Cors cors,
        /** requests with a larger declared body are refused with 413 before they are read */
        @DefaultValue("1MB") DataSize maxBodySize
) {

    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            /** per client address, before authentication: sustained requests per second and burst */
            @DefaultValue("100") double ipPerSecond,
            @DefaultValue("200") int ipBurst,
            /** per authenticated user */
            @DefaultValue("30") double userPerSecond,
            @DefaultValue("60") int userBurst,
            /** the credential endpoints (login, register, service-token): per address, per minute */
            @DefaultValue("20") int authPerMinute
    ) {
    }

    public record Cors(
            /** browser origins allowed to call the API, e.g. https://shop.example.com. Empty: no cross-origin access. */
            @DefaultValue List<String> allowedOrigins,
            @DefaultValue({"GET", "POST", "PUT", "DELETE", "OPTIONS"}) List<String> allowedMethods,
            @DefaultValue({"Authorization", "Content-Type", "Idempotency-Key"}) List<String> allowedHeaders,
            @DefaultValue({"Idempotent-Replayed", "Location", "Retry-After"}) List<String> exposedHeaders,
            @DefaultValue("3600") long maxAgeSeconds
    ) {
    }
}
