package com.mercury.recommendation;

import com.mercury.recommendation.service.EmbeddingService;
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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Two sync passes that overlap in one instance. The index is a stub that holds the first pass in the middle of a push,
 * so the second one certainly arrives while the first is at work, instead of probably.
 */
@SpringBootTest
class IndexSyncOverlapTests {

    /**
     * The three products of the order the test places: coffee, decaf and cocoa. A product's vector is built by hashing the ids
     * of its companions into 64 dimensions, each with a sign, so with random ids two companions sometimes share a dimension
     * with opposite signs and cancel. That product then has no vector and nothing is pushed for it, and the test, which
     * expects a push for each of the three, failed about one run in 43. These ids were taken from the real hash so that each
     * of them falls in a dimension of its own (the check in the test says what that buys); keep them fixed, do not replace
     * them with random ids.
     */
    private static final List<UUID> BOUGHT = List.of(
            UUID.fromString("c0ffee00-0000-4000-8000-000000000001"),
            UUID.fromString("decaf000-0000-4000-8000-000000000001"),
            UUID.fromString("c0c0a000-0000-4000-8000-000000000001"));

    @Autowired private IndexSync sync;
    @Autowired private FeatureStore store;
    @Autowired private EmbeddingService embeddings;
    @Autowired private ScheduledTaskHolder scheduler;
    @MockitoBean private VectorIndex index;

    @Test
    void aPassThatArrivesDuringAnotherWaitsForItAndPushesOnlyWhatIsLeft() throws Exception {
        // The scheduler's own first pass is not one of the two, and what it pushed is not theirs. In a context that has only just
        // started, that pass pushes whatever other tests left unindexed in the shared database, through this same stub; and the
        // stub is cleared after a test, not before the first one, so those pushes would be counted with the ones below.
        Waiting.untilTheStartupSyncIsOver(scheduler);
        clearInvocations(index);
        store.recordPurchase("erin", BOUGHT, Instant.now());
        // what the fixed ids are for, checked with the real embedding on the rows the store holds: each of the three has a vector
        assertThat(BOUGHT).allSatisfy(product -> assertThat(embeddings.embed(store.cooccurrenceRow(product)))
                .as("a vector for %s (the fixed ids no longer fit the embedding?)", product).isPresent());
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
        verify(index, atLeast(BOUGHT.size())).upsert(pushed.capture(), any());
        assertThat(pushed.getAllValues()).containsAll(BOUGHT).doesNotHaveDuplicates();
        assertThat(store.dirtyProducts(1000)).isEmpty();
    }
}
