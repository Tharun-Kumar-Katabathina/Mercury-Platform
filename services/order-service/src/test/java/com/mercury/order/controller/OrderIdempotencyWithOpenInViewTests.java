package com.mercury.order.controller;

import com.mercury.order.client.InventoryClient;
import com.mercury.order.client.ProductClient;
import com.mercury.order.dto.InventoryOperationResult;
import com.mercury.order.dto.ProductDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Regression test. Spring enables open-in-view by default, which binds ONE persistence context
 * to a whole web request. A duplicate request that polls for the first request's result must
 * still see it complete, instead of re-reading a cached "IN_PROGRESS" until it times out.
 * (Found by the real-PostgreSQL integration test, where test config had silently left it on.)
 */
@SpringBootTest(properties = "spring.jpa.open-in-view=true")
@AutoConfigureMockMvc
class OrderIdempotencyWithOpenInViewTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductClient productClient;

    @MockitoBean
    private InventoryClient inventoryClient;

    @Test
    void duplicatesPollingAnInFlightOrderStillSeeItCompleteEvenWithOpenInView() throws Exception {
        UUID productId = UUID.randomUUID();
        when(productClient.getProduct(productId)).thenReturn(
                new ProductDetails(productId, "Laptop", "L-1", new BigDecimal("100.00")));
        // slow reservation: the first request stays IN_PROGRESS long enough for duplicates to poll
        when(inventoryClient.reserve(any(), anyInt(), anyString())).thenAnswer(invocation -> {
            Thread.sleep(400);
            return new InventoryOperationResult(false);
        });

        String key = "key-" + UUID.randomUUID();
        String body = "{\"items\":[{\"productId\":\"" + productId + "\",\"quantity\":1}]}";
        int requests = 60;

        record Reply(int status, String replayed) {
        }

        // one task per request: each returns what it saw, so the pool can never starve itself
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Reply>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                var response = mockMvc.perform(post("/api/v1/orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", key)
                                .content(body))
                        .andReturn().getResponse();
                return new Reply(response.getStatus(), response.getHeader("Idempotent-Replayed"));
            }));
        }
        ready.await();
        go.countDown();

        List<Integer> codes = new ArrayList<>();
        long replays = 0;
        for (Future<Reply> future : futures) {
            Reply reply = future.get(60, TimeUnit.SECONDS);
            codes.add(reply.status());
            if ("true".equals(reply.replayed())) {
                replays++;
            }
        }
        pool.shutdown();

        assertThat(codes).as("no duplicate may time out with 409").allMatch(code -> code == 201);
        assertThat(replays).isEqualTo(requests - 1);
        verify(inventoryClient, times(1)).reserve(any(), anyInt(), anyString());
    }
}
