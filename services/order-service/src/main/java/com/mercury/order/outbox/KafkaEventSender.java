package com.mercury.order.outbox;

import com.mercury.order.config.OutboxProperties;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class KafkaEventSender implements EventSender {

    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties properties;
    private final ObjectProvider<Tracer> tracer;

    public KafkaEventSender(KafkaTemplate<String, String> kafka, OutboxProperties properties, ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
        this.kafka = kafka;
        this.properties = properties;
    }

    @Override
    public void send(OutboxMessage message) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                message.topic(), null, message.key(), message.payload(),
                List.of(new RecordHeader("event-id", message.eventId().toString().getBytes(StandardCharsets.UTF_8)),
                        new RecordHeader("event-type", message.eventType().getBytes(StandardCharsets.UTF_8))));
        Span span = continueTrace(message);
        try (Tracer.SpanInScope ignored = span == null ? null : tracer.getObject().withSpan(span)) {
            // the Kafka template's own observation makes a child span of this one and adds the traceparent header
            kafka.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (span != null) {
                span.error(e);
            }
            throw e;
        } finally {
            if (span != null) {
                span.end();
            }
        }
    }

    /**
     * The publisher runs long after the request that wrote the event. A span whose parent is the request's
     * stored trace context links the asynchronous publish, and every consumer behind it, to the original trace.
     */
    private Span continueTrace(OutboxMessage message) {
        Tracer t = tracer.getIfAvailable();
        String traceParent = message.traceParent();
        if (t == null || traceParent == null) {
            return null;
        }
        String[] parts = traceParent.split("-");
        if (parts.length != 4 || parts[1].length() != 32 || parts[2].length() != 16) {
            return null;                                   // not something we wrote: publish untraced
        }
        TraceContext parent = t.traceContextBuilder()
                .traceId(parts[1]).spanId(parts[2]).sampled("01".equals(parts[3])).build();
        return t.spanBuilder().setParent(parent).name("outbox publish").tag("event.type", message.eventType()).start();
    }
}
