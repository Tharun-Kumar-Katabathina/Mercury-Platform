package com.mercury.recommendation;

import com.mercury.recommendation.service.FeatureStore;
import com.mercury.recommendation.service.IndexSync;
import com.mercury.recommendation.service.VectorIndex;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * A purchase that lands while a sync pass is pushing the same product. The pass built its vector from the profile it read,
 * so the product's "dirty" flag must survive it: clearing the flag would leave the index holding the old profile, with
 * nothing left to say that it is out of date. The index is a stub that holds the push in the middle, so the purchase
 * certainly lands between the pass's read and its marking the product indexed, instead of probably.
 */
@SpringBootTest
class IndexSyncLostUpdateTests {

    /** The ids of IndexSyncOverlapTests.BOUGHT, fixed for the same reason: each of them has a vector of its own to push. */
    private static final List<UUID> BOUGHT = List.of(
            UUID.fromString("c0ffee00-0000-4000-8000-000000000001"),
            UUID.fromString("decaf000-0000-4000-8000-000000000001"),
            UUID.fromString("c0c0a000-0000-4000-8000-000000000001"));

    @Autowired private IndexSync sync;
    @Autowired private FeatureStore store;
    @Autowired private ScheduledTaskHolder scheduler;
    @MockitoBean private VectorIndex index;

    @Test
    void aPurchaseDuringAPushLeavesTheProductDirtyUntilThePassThatSawIt() throws Exception {
        Waiting.untilTheStartupSyncIsOver(scheduler);               // what the first pass pushes is not this test's
        store.recordPurchase("frank", BOUGHT, Instant.now());
        clearInvocations(index);
        AtomicReference<UUID> underWay = new AtomicReference<>();   // the first of the three that a pass pushes
        CountDownLatch aPushIsUnderWay = new CountDownLatch(1);
        CountDownLatch pushMayFinish = new CountDownLatch(1);
        doAnswer(push -> {
            UUID product = push.getArgument(0);
            if (BOUGHT.contains(product) && underWay.compareAndSet(null, product)) {
                aPushIsUnderWay.countDown();
                pushMayFinish.await();
            }
            return null;
        }).when(index).upsert(any(), any());
        FutureTask<Integer> pass = new FutureTask<>(sync::runOnce);

        try {
            new Thread(pass, "pass").start();
            assertThat(aPushIsUnderWay.await(30, TimeUnit.SECONDS)).isTrue();
            store.recordPurchase("grace", BOUGHT, Instant.now());   // lands after the pass read the profile, before it marks it indexed
        } finally {
            pushMayFinish.countDown();
        }
        pass.get(30, TimeUnit.SECONDS);

        UUID held = underWay.get();
        assertThat(store.dirtyProducts(1000)).as("the product that was being pushed when grace's purchase landed").contains(held);
        assertThat(store.dirtyProducts(1000)).as("the others were pushed after it landed, and carry it").doesNotContainAnyElementsOf(
                BOUGHT.stream().filter(product -> !product.equals(held)).toList());

        doAnswer(push -> null).when(index).upsert(any(), any());   // the next pass is not held
        sync.runOnce();

        assertThat(store.dirtyProducts(1000)).as("the next pass pushes the new profile and clears the flag").doesNotContainAnyElementsOf(BOUGHT);
        verify(index, times(2)).upsert(eq(held), any());           // once with the old profile, once with the new one
    }
}
