package com.mercury.product.service;

import com.mercury.product.dto.CreateProductRequest;
import com.mercury.product.dto.ProductResponse;
import com.mercury.product.dto.UpdateProductRequest;
import com.mercury.product.exception.ProductNotFoundException;
import com.mercury.product.repository.ProductRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The Redis read-through cache against a real Redis: hits skip the database, writes evict, and a Redis failure
 * never fails or corrupts a read (the database simply answers).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"product.cache.enabled=true", "product.cache.ttl=30s"})
class ProductCacheTests {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private ProductService service;
    @Autowired private StringRedisTemplate redis;
    @Autowired private MeterRegistry meters;
    @MockitoSpyBean private ProductRepository repository;

    private ProductResponse newProduct() {
        return service.createProduct(new CreateProductRequest(
                "Cached item", "SKU-" + UUID.randomUUID(), new BigDecimal("19.99"), 5));
    }

    private double count(String result) {
        var counter = meters.find("product.cache").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void theSecondReadIsServedFromRedisWithoutTouchingTheDatabase() {
        ProductResponse created = newProduct();
        double hits = count("hit");
        clearInvocations(repository);

        ProductResponse first = service.getProduct(created.id());     // miss: loads and caches
        ProductResponse second = service.getProduct(created.id());    // hit

        assertThat(second).isEqualTo(first).isEqualTo(created);
        verify(repository, times(1)).findById(created.id());
        assertThat(count("hit")).isEqualTo(hits + 1);
        assertThat(redis.hasKey("mercury:product:" + created.id())).isTrue();
    }

    @Test
    void anUpdateEvictsSoTheNextReadSeesTheNewPrice() {
        ProductResponse created = newProduct();
        service.getProduct(created.id());                              // cached

        service.updateProduct(created.id(), new UpdateProductRequest("Renamed", new BigDecimal("29.99"), 7));

        assertThat(redis.hasKey("mercury:product:" + created.id())).isFalse();
        ProductResponse fresh = service.getProduct(created.id());
        assertThat(fresh.price()).isEqualByComparingTo("29.99");
        assertThat(fresh.name()).isEqualTo("Renamed");
    }

    @Test
    void aDeleteEvictsSoANotFoundIsNeverHiddenByTheCache() {
        ProductResponse created = newProduct();
        service.getProduct(created.id());

        service.deleteProduct(created.id());

        assertThatThrownBy(() -> service.getProduct(created.id())).isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void anUnknownProductIsNeverCached() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> service.getProduct(unknown)).isInstanceOf(ProductNotFoundException.class);

        assertThat(redis.hasKey("mercury:product:" + unknown)).isFalse();
    }

    @Test
    void aGarbledCacheEntryIsTreatedAsAMissAndRepaired() {
        ProductResponse created = newProduct();
        redis.opsForValue().set("mercury:product:" + created.id(), "{not json");
        double errors = count("error");

        ProductResponse read = service.getProduct(created.id());

        assertThat(read).isEqualTo(created);                          // answered from the database
        assertThat(count("error")).isEqualTo(errors + 1);
        assertThat(service.getProduct(created.id())).isEqualTo(created);   // and the entry was rewritten
    }

    @Test
    void whenRedisIsDownReadsStillWorkFromTheDatabase() {
        ProductResponse created = newProduct();
        double errors = count("error");
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            ProductResponse read = service.getProduct(created.id());
            assertThat(read).isEqualTo(created);
            assertThat(count("error")).isGreaterThan(errors);
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }
}
