package com.mercury.order.client;

import com.mercury.order.config.ResilienceProperties;
import com.mercury.order.exception.InventoryServiceException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DownstreamGuardTests {

    private static ResilienceProperties.Downstream settings(int minimumCalls, Duration openFor, int maxConcurrent) {
        return new ResilienceProperties.Downstream(50f, Math.max(10, minimumCalls), minimumCalls, openFor, 2, maxConcurrent, Duration.ZERO);
    }

    private static DownstreamGuard guard(int minimumCalls, Duration openFor, int maxConcurrent) {
        return DownstreamGuard.create("inventory", settings(minimumCalls, openFor, maxConcurrent), new SimpleMeterRegistry());
    }

    private static InventoryServiceException rejected(Throwable cause) {
        return new InventoryServiceException(HttpStatus.SERVICE_UNAVAILABLE, null, cause, true);
    }

    private static void fail(DownstreamGuard guard, HttpStatus status) {
        assertThatThrownBy(() -> guard.execute(() -> {
            throw new InventoryServiceException(status, null);
        }, DownstreamGuardTests::rejected)).isInstanceOf(InventoryServiceException.class);
    }

    @Test
    void passesResultsThroughWhileHealthy() {
        DownstreamGuard guard = guard(3, Duration.ofSeconds(10), 5);

        assertThat(guard.execute(() -> "ok", DownstreamGuardTests::rejected)).isEqualTo("ok");
        assertThat(guard.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void opensAfterRepeatedServerFailuresAndThenFailsFastWithoutCallingTheService() {
        DownstreamGuard guard = guard(3, Duration.ofSeconds(10), 5);
        for (int i = 0; i < 3; i++) {
            fail(guard, HttpStatus.SERVICE_UNAVAILABLE);
        }
        assertThat(guard.state()).isEqualTo(CircuitBreaker.State.OPEN);

        int[] calls = {0};
        assertThatThrownBy(() -> guard.execute(() -> {
            calls[0]++;
            return "should not run";
        }, DownstreamGuardTests::rejected))
                .isInstanceOfSatisfying(InventoryServiceException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(503);
                    assertThat(e.wasNeverSent()).isTrue();     // certain: nothing reached the service
                });
        assertThat(calls[0]).isZero();
    }

    @Test
    void businessAnswersDoNotTripTheCircuit() {
        DownstreamGuard guard = guard(3, Duration.ofSeconds(10), 5);

        for (int i = 0; i < 20; i++) {
            fail(guard, HttpStatus.CONFLICT);        // 409 INSUFFICIENT_STOCK is a healthy "no"
            fail(guard, HttpStatus.NOT_FOUND);
        }

        assertThat(guard.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void halfOpensAfterTheWaitAndClosesAgainOnSuccess() throws Exception {
        DownstreamGuard guard = guard(3, Duration.ofMillis(200), 5);
        for (int i = 0; i < 3; i++) {
            fail(guard, HttpStatus.INTERNAL_SERVER_ERROR);
        }
        assertThat(guard.state()).isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(300);                                            // wait-duration-open elapsed
        assertThat(guard.execute(() -> "trial 1", DownstreamGuardTests::rejected)).isEqualTo("trial 1");
        assertThat(guard.execute(() -> "trial 2", DownstreamGuardTests::rejected)).isEqualTo("trial 2");

        assertThat(guard.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void aFailedTrialReopensTheCircuit() throws Exception {
        DownstreamGuard guard = guard(3, Duration.ofMillis(200), 5);
        for (int i = 0; i < 3; i++) {
            fail(guard, HttpStatus.SERVICE_UNAVAILABLE);
        }
        Thread.sleep(300);

        fail(guard, HttpStatus.SERVICE_UNAVAILABLE);   // trial fails
        fail(guard, HttpStatus.SERVICE_UNAVAILABLE);   // second trial fails: half-open window evaluated

        assertThat(guard.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void theBulkheadRejectsCallsBeyondItsLimitInsteadOfPilingUp() throws Exception {
        DownstreamGuard guard = guard(100, Duration.ofSeconds(10), 2);
        CountDownLatch bothInside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                Future<?> slow = pool.submit(() -> guard.execute(() -> {
                    bothInside.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "slow";
                }, DownstreamGuardTests::rejected));
                assertThat(slow).isNotNull();
            }
            assertThat(bothInside.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> guard.execute(() -> "third", DownstreamGuardTests::rejected))
                    .isInstanceOfSatisfying(InventoryServiceException.class,
                            e -> assertThat(e.wasNeverSent()).isTrue());
        } finally {
            release.countDown();
            pool.shutdown();
        }
    }

    @Test
    void passThroughAddsNothing() {
        DownstreamGuard guard = DownstreamGuard.passThrough();

        assertThat(guard.execute(() -> "x", DownstreamGuardTests::rejected)).isEqualTo("x");
        assertThat(guard.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void refusesAMinimumCallCountLargerThanTheWindowInsteadOfSilentlyCappingIt() {
        ResilienceProperties.Downstream contradictory = new ResilienceProperties.Downstream(
                50f, 20, 1000, Duration.ofSeconds(10), 3, 25, Duration.ZERO);

        assertThatThrownBy(() -> DownstreamGuard.create("inventory", contradictory, new SimpleMeterRegistry()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum-calls")
                .hasMessageContaining("sliding-window-size");
    }
}
