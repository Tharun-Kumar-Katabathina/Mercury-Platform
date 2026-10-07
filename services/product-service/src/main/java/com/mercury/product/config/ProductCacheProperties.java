package com.mercury.product.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** product.cache.*: the optional Redis read-through cache for GET /products/{id}. */
@ConfigurationProperties(prefix = "product.cache")
public record ProductCacheProperties(
        /** off by default: the service works exactly as before without Redis */
        @DefaultValue("false") boolean enabled,
        /** how long a cached product is served; updates and deletes evict it immediately */
        @DefaultValue("60s") Duration ttl,
        @DefaultValue("mercury:product:") String keyPrefix
) {
}
