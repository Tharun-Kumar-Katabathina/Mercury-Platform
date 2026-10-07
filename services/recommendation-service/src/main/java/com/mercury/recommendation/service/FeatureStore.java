package com.mercury.recommendation.service;

import com.mercury.recommendation.config.RecommendationProperties;
import com.mercury.recommendation.model.InteractionType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * All reads and writes of the features, in plain SQL. Increments use the standard SQL MERGE (PostgreSQL 15+ and H2),
 * so they are single atomic statements: two consumers updating the same pair cannot lose each other's update.
 */
@Repository
public class FeatureStore {

    private final JdbcClient jdbc;
    private final RecommendationProperties.Scoring scoring;
    private final Clock clock;

    public FeatureStore(JdbcClient jdbc, RecommendationProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.scoring = properties.scoring();
        this.clock = clock;
    }

    // ---- events ------------------------------------------------------------------------------------------

    /** @return false if this event was already handled (the caller then does nothing) */
    @Transactional
    public boolean markProcessed(UUID eventId) {
        return jdbc.sql("""
                insert into processed_events (event_id, processed_at)
                select :id, :now where not exists (select 1 from processed_events where event_id = :id)
                """).param("id", eventId).param("now", ts(Instant.now(clock))).update() == 1;
    }

    @Transactional
    public void savePending(UUID orderId, String customerId, String itemsJson) {
        jdbc.sql("""
                insert into pending_orders (order_id, customer_id, items, created_at)
                select :id, :customer, :items, :now where not exists (select 1 from pending_orders where order_id = :id)
                """).param("id", orderId).param("customer", customerId).param("items", itemsJson)
                .param("now", ts(Instant.now(clock))).update();
    }

    public record Pending(UUID orderId, String customerId, String itemsJson) { }

    @Transactional
    public java.util.Optional<Pending> takePending(UUID orderId) {
        java.util.Optional<Pending> pending = jdbc.sql("select order_id, customer_id, items from pending_orders where order_id = :id")
                .param("id", orderId)
                .query((rs, i) -> new Pending(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).optional();
        pending.ifPresent(p -> jdbc.sql("delete from pending_orders where order_id = :id").param("id", orderId).update());
        return pending;
    }

    // ---- behaviour -------------------------------------------------------------------------------------------

    @Transactional
    public void recordInteraction(String userId, UUID productId, InteractionType type, Instant at) {
        jdbc.sql("insert into interactions (id, user_id, product_id, type, occurred_at) values (:id, :user, :product, :type, :at)")
                .param("id", UUID.randomUUID()).param("user", userId).param("product", productId)
                .param("type", type.name()).param("at", ts(at)).update();
        addAffinity(userId, productId, weight(type), type == InteractionType.PURCHASE, at);
    }

    public double weight(InteractionType type) {
        return switch (type) {
            case VIEW -> scoring.viewWeight();
            case ADD_TO_CART -> scoring.cartWeight();
            case PURCHASE -> scoring.purchaseWeight();
        };
    }

    private void addAffinity(String userId, UUID productId, double delta, boolean purchased, Instant at) {
        jdbc.sql("""
                merge into user_affinity t
                using (values (cast(:user as varchar(100)), cast(:product as uuid), cast(:delta as double precision),
                               cast(:purchased as boolean), cast(:at as timestamp with time zone))) s (user_id, product_id, delta, purchased, at)
                on t.user_id = s.user_id and t.product_id = s.product_id
                when matched then update set score = t.score + s.delta, purchased = (t.purchased or s.purchased), updated_at = s.at
                when not matched then insert (user_id, product_id, score, purchased, updated_at) values (s.user_id, s.product_id, s.delta, s.purchased, s.at)
                """).param("user", userId).param("product", productId).param("delta", delta)
                .param("purchased", purchased).param("at", ts(at)).update();
    }

    /** A confirmed order: every pair of its products was bought together, every product was bought. */
    @Transactional
    public void recordPurchase(String customerId, List<UUID> products, Instant at) {
        List<UUID> distinct = products.stream().distinct().toList();
        for (UUID product : distinct) {
            jdbc.sql("""
                    merge into product_stats t
                    using (values (cast(:product as uuid))) s (product_id)
                    on t.product_id = s.product_id
                    when matched then update set purchases = t.purchases + 1, index_dirty = true
                    when not matched then insert (product_id, purchases, index_dirty) values (s.product_id, 1, true)
                    """).param("product", product).update();
            if (customerId != null) {
                recordInteraction(customerId, product, InteractionType.PURCHASE, at);
            }
        }
        for (UUID a : distinct) {
            for (UUID b : distinct) {
                if (!a.equals(b)) {
                    jdbc.sql("""
                            merge into cooccurrence t
                            using (values (cast(:a as uuid), cast(:b as uuid))) s (product_id, other_id)
                            on t.product_id = s.product_id and t.other_id = s.other_id
                            when matched then update set score = t.score + 1
                            when not matched then insert (product_id, other_id, score) values (s.product_id, s.other_id, 1)
                            """).param("a", a).param("b", b).update();
                }
            }
        }
    }

    // ---- reads -------------------------------------------------------------------------------------------

    /** the co-purchase profile of a product: who it was bought with, and how often */
    public Map<UUID, Double> cooccurrenceRow(UUID productId) {
        Map<UUID, Double> row = new LinkedHashMap<>();
        jdbc.sql("select other_id, score from cooccurrence where product_id = :p order by score desc")
                .param("p", productId).query((rs, i) -> { row.put(rs.getObject(1, UUID.class), rs.getDouble(2)); return null; }).list();
        return row;
    }

    public record Scored(UUID productId, double score) { }

    public List<Scored> boughtTogether(UUID productId, int limit) {
        return jdbc.sql("select other_id, score from cooccurrence where product_id = :p order by score desc, other_id limit :n")
                .param("p", productId).param("n", limit)
                .query((rs, i) -> new Scored(rs.getObject(1, UUID.class), rs.getDouble(2))).list();
    }

    public List<Scored> popular(int limit) {
        return jdbc.sql("select product_id, purchases from product_stats order by purchases desc, product_id limit :n")
                .param("n", limit).query((rs, i) -> new Scored(rs.getObject(1, UUID.class), rs.getDouble(2))).list();
    }

    public record Affinity(UUID productId, double score, boolean purchased) { }

    /** a customer's strongest products, with older interactions counting for less (half-life decay) */
    public List<Affinity> topAffinities(String userId, int limit) {
        Instant now = Instant.now(clock);
        return jdbc.sql("select product_id, score, purchased, updated_at from user_affinity where user_id = :u")
                .param("u", userId)
                .query((rs, i) -> {
                    double ageDays = Duration.between(rs.getTimestamp(4).toInstant(), now).toMillis() / 86_400_000d;
                    double decayed = rs.getDouble(2) * Math.pow(0.5, Math.max(0, ageDays) / scoring.halfLifeDays());
                    return new Affinity(rs.getObject(1, UUID.class), decayed, rs.getBoolean(3));
                }).list().stream()
                .sorted((x, y) -> Double.compare(y.score(), x.score()))
                .limit(limit).toList();
    }

    public List<UUID> purchasedBy(String userId) {
        return jdbc.sql("select product_id from user_affinity where user_id = :u and purchased = true")
                .param("u", userId).query((rs, i) -> rs.getObject(1, UUID.class)).list();
    }

    // ---- index maintenance ----------------------------------------------------------------------------

    public List<UUID> dirtyProducts(int limit) {
        return jdbc.sql("select product_id from product_stats where index_dirty = true order by product_id limit :n")
                .param("n", limit).query((rs, i) -> rs.getObject(1, UUID.class)).list();
    }

    @Transactional
    public void markIndexed(UUID productId) {
        jdbc.sql("update product_stats set index_dirty = false, indexed_at = :now where product_id = :p")
                .param("now", ts(Instant.now(clock))).param("p", productId).update();
    }

    public long count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
