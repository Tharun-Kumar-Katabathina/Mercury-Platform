package com.mercury.order.outbox;

import com.mercury.order.client.InventoryClient;
import com.mercury.order.client.ProductClient;
import com.mercury.order.config.OutboxProperties;
import com.mercury.order.dto.CreateOrderItemRequest;
import com.mercury.order.dto.CreateOrderRequest;
import com.mercury.order.dto.InventoryOperationResult;
import com.mercury.order.dto.ProductDetails;
import com.mercury.order.service.OrderService;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The trace of the request that created an order must continue through the outbox: the event row remembers the
 * request's trace context, and the publisher (which runs later, on another thread) sends inside a span of that
 * same trace, so the consumers behind Kafka belong to the same distributed trace.
 */
@SpringBootTest
class OutboxTraceContextTests {

    @Autowired private OrderService orderService;
    @Autowired private OutboxRepository outbox;
    @Autowired private Tracer tracer;
    @Autowired private ObjectProvider<Tracer> tracerProvider;
    @Autowired private OutboxProperties properties;

    @MockitoBean private ProductClient productClient;
    @MockitoBean private InventoryClient inventoryClient;

    private UUID placeOrderInsideASpan(Span span) {
        UUID product = UUID.randomUUID();
        when(productClient.getProduct(product))
                .thenReturn(new ProductDetails(product, "Item", "SKU-" + product, new BigDecimal("10.00")));
        when(inventoryClient.reserve(any(), anyInt(), anyString())).thenReturn(new InventoryOperationResult(false));
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return orderService.createOrder("trace-" + UUID.randomUUID(),
                    new CreateOrderRequest(List.of(new CreateOrderItemRequest(product, 1)))).order().id();
        }
    }

    @Test
    void everyEventRowRemembersTheTraceOfTheRequestThatWroteIt() {
        Span request = tracer.nextSpan().name("test request").start();
        UUID orderId = placeOrderInsideASpan(request);
        request.end();

        List<OutboxEvent> events = outbox.findByAggregateIdOrderBySeq(orderId);
        assertThat(events).hasSize(2);   // OrderCreated, OrderConfirmed
        assertThat(events).allSatisfy(e -> assertThat(e.getTraceParent())
                .matches("00-" + request.context().traceId() + "-[0-9a-f]{16}-0[01]"));
    }

    @Test
    void thePublisherSendsInsideASpanOfThatSameTrace() throws Exception {
        Span request = tracer.nextSpan().name("test request").start();
        UUID orderId = placeOrderInsideASpan(request);
        request.end();
        OutboxEvent event = outbox.findByAggregateIdOrderBySeq(orderId).get(0);

        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        AtomicReference<String> traceDuringSend = new AtomicReference<>();
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            Span current = tracer.currentSpan();
            traceDuringSend.set(current == null ? null : current.context().traceId());
            return CompletableFuture.completedFuture((SendResult<String, String>) null);
        });
        KafkaEventSender sender = new KafkaEventSender(kafka, properties, tracerProvider);

        sender.send(new OutboxMessage(event.getSeq(), event.getEventId(), orderId, event.getEventType(),
                "topic", event.getPayload(), event.getTraceParent()));

        assertThat(traceDuringSend.get()).isEqualTo(request.context().traceId());
    }

    @Test
    void anEventWithoutATraceContextIsStillPublished() throws Exception {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        KafkaEventSender sender = new KafkaEventSender(kafka, properties, tracerProvider);

        sender.send(new OutboxMessage(1, UUID.randomUUID(), UUID.randomUUID(), "OrderCreated", "t", "{}", null));
        sender.send(new OutboxMessage(2, UUID.randomUUID(), UUID.randomUUID(), "OrderCreated", "t", "{}", "garbage"));
    }
}
