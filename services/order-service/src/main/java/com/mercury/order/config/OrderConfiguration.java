package com.mercury.order.config;

import com.mercury.order.client.DownstreamGuard;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({RecoveryProperties.class, ResilienceProperties.class, OutboxProperties.class, ReservationProperties.class})
public class OrderConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public DownstreamGuard productGuard(ResilienceProperties properties, MeterRegistry meters) {
        return DownstreamGuard.create("product", properties.product(), meters);
    }

    @Bean
    public DownstreamGuard inventoryGuard(ResilienceProperties properties, MeterRegistry meters) {
        return DownstreamGuard.create("inventory", properties.inventory(), meters);
    }
}
