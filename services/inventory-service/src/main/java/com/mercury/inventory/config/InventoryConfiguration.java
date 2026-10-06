package com.mercury.inventory.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class InventoryConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
