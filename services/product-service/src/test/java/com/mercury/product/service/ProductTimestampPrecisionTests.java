package com.mercury.product.service;

import com.mercury.product.dto.CreateProductRequest;
import com.mercury.product.dto.ProductResponse;
import com.mercury.product.model.Product;
import com.mercury.product.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * How fine the clock is depends on the host: microseconds on macOS, nanoseconds on Linux. The timestamp columns keep
 * microseconds, so a product stamped straight from a nanosecond clock came back from the database different from the
 * one just handed to the caller, but only on Linux. Here the clock is always finer than the columns, so the
 * difference would show on every machine.
 */
@SpringBootTest
class ProductTimestampPrecisionTests {

    /** both have digits below the microsecond; the database would round the first one UP, to ...07.777891 */
    private static final Instant CREATED = Instant.parse("2026-10-09T00:03:07.777890581Z");
    private static final Instant UPDATED = Instant.parse("2026-10-09T00:03:08.762714169Z");

    @Autowired private ProductService service;
    @Autowired private ProductRepository repository;
    @MockitoBean private Clock clock;

    private ProductResponse newProductAt(Instant now) {
        when(clock.instant()).thenReturn(now);
        return service.createProduct(new CreateProductRequest(
                "Precise item", "SKU-" + UUID.randomUUID(), new BigDecimal("19.99"), 5));
    }

    @Test
    void aNewProductIsReturnedExactlyAsItIsStored() {
        ProductResponse created = newProductAt(CREATED);

        assertThat(service.getProduct(created.id())).isEqualTo(created);
    }

    @Test
    void theStampIsCutToMicrosecondsNotRounded() {
        ProductResponse created = newProductAt(CREATED);

        assertThat(created.createdAt()).isEqualTo(Instant.parse("2026-10-09T00:03:07.777890Z"));
        assertThat(created.updatedAt()).isEqualTo(created.createdAt());
    }

    @Test
    void anUpdateIsStampedAtTheStoredPrecisionToo() {
        ProductResponse created = newProductAt(CREATED);
        Product product = repository.findById(created.id()).orElseThrow();
        product.setName("Renamed");
        when(clock.instant()).thenReturn(UPDATED);

        Product written = repository.saveAndFlush(product);                // the update stamp is set in this flush

        Product stored = repository.findById(created.id()).orElseThrow();
        assertThat(written.getUpdatedAt()).isEqualTo(Instant.parse("2026-10-09T00:03:08.762714Z"));
        assertThat(stored.getUpdatedAt()).isEqualTo(written.getUpdatedAt());
        assertThat(stored.getCreatedAt()).isEqualTo(created.createdAt());
    }
}
