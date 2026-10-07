package com.mercury.product.service;

import com.mercury.product.dto.CreateProductRequest;
import com.mercury.product.dto.ProductResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The product list is never unbounded: it is paged, in a stable order, with a hard cap on the page size. */
@SpringBootTest
class ProductListPagingTests {

    @Autowired private ProductService service;

    private void createProducts(int n) {
        IntStream.range(0, n).forEach(i -> service.createProduct(new CreateProductRequest(
                "Paged " + i, "PAGE-" + UUID.randomUUID(), new BigDecimal("1.00"), 1)));
    }

    @Test
    void pagesDoNotOverlapAndAreInAStableOrder() {
        createProducts(25);

        List<ProductResponse> first = service.getAllProducts(0, 10);
        List<ProductResponse> second = service.getAllProducts(1, 10);
        List<ProductResponse> again = service.getAllProducts(0, 10);

        assertThat(first).hasSize(10);
        assertThat(second).hasSize(10);
        assertThat(first).isEqualTo(again);
        assertThat(first).extracting(ProductResponse::id).doesNotContainAnyElementsOf(second.stream().map(ProductResponse::id).toList());
    }

    @Test
    void theSizeIsCappedSoNoRequestCanAskForEverything() {
        createProducts(5);

        assertThat(service.getAllProducts(0, 1_000_000).size()).isLessThanOrEqualTo(ProductService.MAX_PAGE_SIZE);
        assertThat(service.getAllProducts(0, 0)).hasSizeLessThanOrEqualTo(1);       // below 1 is treated as 1
        assertThat(service.getAllProducts(-5, 10)).isNotEmpty();                    // a negative page is page 0
    }

    @Test
    void aPageBeyondTheEndIsEmptyNotAnError() {
        assertThat(service.getAllProducts(100_000, 50)).isEmpty();
    }
}
