package com.mercury.recommendation;

import com.mercury.recommendation.service.FeatureStore;
import com.mercury.recommendation.service.OrderEventHandler;
import com.mercury.recommendation.service.OrderEventHandler.Result;
import com.mercury.recommendation.exception.InvalidEventException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Learning from order events: only confirmed orders teach anything, and each event is applied exactly once. */
@SpringBootTest
class OrderEventHandlerTests {

    @Autowired private OrderEventHandler handler;
    @Autowired private FeatureStore store;

    private static String created(UUID eventId, UUID orderId, String customer, UUID... products) {
        StringBuilder items = new StringBuilder();
        for (UUID p : products) {
            if (items.length() > 0) items.append(',');
            items.append("{\"productId\":\"").append(p).append("\",\"quantity\":1}");
        }
        return "{\"eventId\":\"%s\",\"eventType\":\"OrderCreated\",\"occurredAt\":\"2026-10-06T10:00:00Z\",\"orderId\":\"%s\",\"items\":[%s],\"totalAmount\":10,\"customerId\":%s}"
                .formatted(eventId, orderId, items, customer == null ? "null" : "\"" + customer + "\"");
    }

    private static String simple(String type, UUID eventId, UUID orderId) {
        return "{\"eventId\":\"%s\",\"eventType\":\"%s\",\"occurredAt\":\"2026-10-06T10:00:00Z\",\"orderId\":\"%s\"}".formatted(eventId, type, orderId);
    }

    @Test
    void aConfirmedOrderTeachesWhatWasBoughtTogetherAndWhoBoughtIt() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), order = UUID.randomUUID();
        String customer = "cust-" + UUID.randomUUID();

        assertThat(handler.handle(created(UUID.randomUUID(), order, customer, a, b, c))).isEqualTo(Result.REMEMBERED);
        assertThat(handler.handle(simple("OrderConfirmed", UUID.randomUUID(), order))).isEqualTo(Result.LEARNED);

        assertThat(store.boughtTogether(a, 10)).extracting(FeatureStore.Scored::productId).containsExactlyInAnyOrder(b, c);
        assertThat(store.boughtTogether(a, 10)).allSatisfy(s -> assertThat(s.score()).isEqualTo(1.0));
        assertThat(store.dirtyProducts(1000)).contains(a, b, c);
        assertThat(store.purchasedBy(customer)).containsExactlyInAnyOrder(a, b, c);
        assertThat(store.topAffinities(customer, 10)).hasSize(3).allSatisfy(x -> assertThat(x.score()).isCloseTo(5.0, org.assertj.core.data.Offset.offset(0.01)));
    }

    @Test
    void aCancelledOrderTeachesNothing() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), order = UUID.randomUUID();
        handler.handle(created(UUID.randomUUID(), order, "cust-x", a, b));

        assertThat(handler.handle(simple("OrderCancelled", UUID.randomUUID(), order))).isEqualTo(Result.FORGOTTEN);

        assertThat(store.boughtTogether(a, 10)).isEmpty();
        assertThat(handler.handle(simple("OrderConfirmed", UUID.randomUUID(), order))).isEqualTo(Result.IGNORED);   // too late
    }

    @Test
    void aRedeliveredEventChangesNothing() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), order = UUID.randomUUID();
        UUID createdId = UUID.randomUUID(), confirmedId = UUID.randomUUID();
        handler.handle(created(createdId, order, "cust-y", a, b));
        handler.handle(simple("OrderConfirmed", confirmedId, order));

        assertThat(handler.handle(created(createdId, order, "cust-y", a, b))).isEqualTo(Result.DUPLICATE);
        assertThat(handler.handle(simple("OrderConfirmed", confirmedId, order))).isEqualTo(Result.DUPLICATE);

        assertThat(store.boughtTogether(a, 10)).singleElement().satisfies(s -> assertThat(s.score()).isEqualTo(1.0));
    }

    @Test
    void repeatedPurchasesStrengthenThePattern() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            UUID order = UUID.randomUUID();
            handler.handle(created(UUID.randomUUID(), order, "cust-z" + i, a, b));
            handler.handle(simple("OrderConfirmed", UUID.randomUUID(), order));
        }

        assertThat(store.boughtTogether(a, 10)).singleElement().satisfies(s -> assertThat(s.score()).isEqualTo(3.0));
        assertThat(store.popular(1000).stream().filter(p -> p.productId().equals(a)).findFirst().orElseThrow().score()).isEqualTo(3.0);
    }

    @Test
    void anOrderWithoutACustomerStillTeachesProductPatternsButNoPersonalAffinity() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), order = UUID.randomUUID();
        handler.handle(created(UUID.randomUUID(), order, null, a, b));
        handler.handle(simple("OrderConfirmed", UUID.randomUUID(), order));

        assertThat(store.boughtTogether(a, 10)).hasSize(1);
    }

    @Test
    void otherEventsAreIgnoredAndBrokenOnesAreRefusedForGood() {
        assertThat(handler.handle(simple("SomethingElse", UUID.randomUUID(), UUID.randomUUID()))).isEqualTo(Result.IGNORED);
        assertThatThrownBy(() -> handler.handle("not json")).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> handler.handle("{\"eventType\":\"OrderCreated\",\"orderId\":\"%s\"}".formatted(UUID.randomUUID())))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> handler.handle("{\"eventType\":\"OrderConfirmed\",\"eventId\":\"x\",\"orderId\":\"%s\"}".formatted(UUID.randomUUID())))
                .isInstanceOf(InvalidEventException.class);
    }
}
