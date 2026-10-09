package com.mercury.product.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(ProductCacheProperties.class)
public class ProductConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
