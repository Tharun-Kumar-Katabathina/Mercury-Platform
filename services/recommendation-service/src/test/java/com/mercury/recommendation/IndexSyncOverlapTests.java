package com.mercury.recommendation;

import com.mercury.recommendation.service.FeatureStore;
import com.mercury.recommendation.service.IndexSync;
import com.mercury.recommendation.service.VectorIndex;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Two sync passes that overlap in one instance. The index is a stub that holds the first pass in the middle of a push,
 * so the second one certainly arrives while the first is at work, instead of probably.
 */
@SpringBootTest
class IndexSyncOverlapTests {

    @Autowired private IndexSync sync;
    @Autowired private FeatureStore store;
    @Autowired private ScheduledTaskHolder scheduler;
    @MockitoBean private VectorIndex index;

    @Test
    void aPassThatArrivesDuringAnotherWaitsForItAndPushesOnlyWhatIsLeft() throws Exception {
        Waiting.untilTheStartupSyncIsOver(scheduler);                      // the scheduler's own first pass is not one of the two
        List<UUID> bought = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        store.recordPurchase("erin", bought, Instant.now());
        int dirty = store.dirtyProducts(1000).size();                      // those three, and whatever other tests left behind
        CountDownLatch aPushIsUnderWay = new CountDownLatch(1);
        CountDownLatch pushesMayFinish = new CountDownLatch(1);
        doAnswer(push -> {
            aPushIsUnderWay.countDown();
            pushesMayFinish.await();
            return null;
        }).when(index).upsert(any(), any());
        FutureTask<Integer> first = new FutureTask<>(sync::runOnce);
        FutureTask<Integer> second = new FutureTask<>(sync::runOnce);
        Thread secondPass = new Thread(second, "second-pass");

        try {
            new Thread(first, "first-pass").start();
            assertThat(aPushIsUnderWay.await(30, TimeUnit.SECONDS)).isTrue();
            secondPass.start();
            Waiting.untilParkedIn(secondPass, IndexSync.class, "runOnce");

            verify(index, times(1)).upsert(any(), any());                   // the second pass has not pushed anything of its own
        } finally {
            pushesMayFinish.countDown();
        }

        assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(dirty);      // the first pass pushed all that was dirty when it started
        assertThat(second.get(30, TimeUnit.SECONDS)).isZero();             // the second found nothing left, and reports just that
        ArgumentCaptor<UUID> pushed = ArgumentCaptor.forClass(UUID.class);
        verify(index, atLeast(bought.size())).upsert(pushed.capture(), any());
        assertThat(pushed.getAllValues()).containsAll(bought).doesNotHaveDuplicates();
        assertThat(store.dirtyProducts(1000)).isEmpty();
    }
}
