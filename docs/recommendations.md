# Recommendation and data platform (Phase 16)

The recommendation service learns from what customers actually buy and does three things with it: finds products similar to a given
one, finds what is bought together, and recommends products to a specific customer. It is built from real components end to end:
Kafka (learning), PostgreSQL (features), Qdrant (vector search), Redis (cache).

```
Order Service ── OrderCreated / OrderConfirmed / OrderCancelled ──> Kafka (mercury.order.events)
                                                                      │
                                    Recommendation Service  <────────┘   idempotent consumer, bounded retries, DLQ
                                      │ 1. features (PostgreSQL): affinities, co-purchase counts, popularity
                                      │ 2. vectors: each product's co-purchase profile hashed to 64 dimensions
                                      ▼
                                    Qdrant (cosine nearest neighbours)         Redis (cached answers, TTL)
                                      ▲                                           ▲
Client ── GET /api/v1/recommendations/... via the gateway ───────────────────────┘
```

## 1. Learning

- **Orders become purchases only when confirmed.** `OrderCreated` (which carries the items and, since this phase, the customer id) is
  remembered; `OrderConfirmed` turns it into a purchase; `OrderCancelled` forgets it. A cancelled or still-pending order teaches nothing.
- **Each event is applied exactly once**: its id is recorded in the same transaction as its effect. Redelivery changes nothing. Malformed
  events skip the retries and go to a dead-letter topic, and never block the events behind them.
- **The service declares the topic it consumes**, `mercury.order.events`, with three partitions, and that is applied before its consumer
  starts. On a fresh broker a consumer that subscribes first makes the broker create the topic with *one* partition; when the
  Notification Service then declares it with three, the consumer that is already there does not see the new partitions until its next
  metadata refresh (five minutes), and learns nothing from the orders on them until then. The partition count is the Notification
  Service's `NOTIFICATION_TOPIC_PARTITIONS`: one variable for both declarations, so set it for both services or for neither.
- **Behaviour**: `POST /api/v1/interactions` (`VIEW`, `ADD_TO_CART`) records what the *authenticated* customer did; the customer is always
  the token subject, and clients cannot report purchases.
- What it stores (all rebuildable from events):

| Table | Meaning |
|---|---|
| `user_affinity` | how much a customer cares about a product: view 1, cart 3, purchase 5, **half-life 30 days** (older interest counts for less) |
| `cooccurrence` | how many orders contained both products (stored in both directions, updated with one atomic `MERGE`) |
| `product_stats` | purchase counts (popularity) and whether the product's vector in the index is out of date |
| `pending_orders`, `processed_events`, `interactions` | bookkeeping and the raw interaction log |

## 2. Similar products: vectors in Qdrant

A product's **co-purchase profile** (which other products it was bought with, and how often) is turned into a fixed-length vector by
feature hashing: each companion adds `log(1 + count)` to one of 64 dimensions with a pseudo-random sign (FNV-1a, stable across JVMs), then the
vector is normalised. Two products bought with the *same companions* get nearby vectors even if they were **never bought together**, which is
the classic item-to-item collaborative-filtering signal; Qdrant finds the nearest neighbours by cosine similarity. There is no training
step: the vector is a pure function of the counts, so it can be rebuilt at any time.

Index maintenance is outbox-like: a purchase marks the affected products `index_dirty` **in the same transaction**, and `IndexSync` pushes
their vectors in batches. If Qdrant is down, the flag stays set and the next run catches up, so an index outage delays recommendations but
never loses an update (tested against a paused Qdrant container).

## 3. The three questions

| Endpoint | Answer | Source |
|---|---|---|
| `GET /recommendations/products/{id}/similar` | products bought with the same companions | Qdrant nearest neighbours (cached 5 min) |
| `GET /recommendations/products/{id}/bought-together` | products most often in the same order | co-purchase counts |
| `GET /recommendations/me` | personalised for the caller | see below (cached 60 s) |
| `GET /recommendations/popular` | most purchased overall (public) | purchase counts |

**Personalised**: take the customer's strongest products (decayed by age); each contributes its similar and bought-together products,
weighted by the customer's affinity; remove anything they already bought; rank; if there is too little history (a new customer), pad with
what is popular. Limits are capped (default 50).

## 4. Failure behaviour

- **Redis down**: recommendations are computed instead of cached (a miss, never an error; 200 ms timeouts). Tested with a paused Redis.
- **Qdrant down**: `similar` and `me` return `503 RECOMMENDATIONS_UNAVAILABLE` with `Retry-After`; everything else keeps working, and nothing is lost (see above).
- **Kafka down / consumer restarts**: events wait in Kafka; the consumer resumes from its offset and de-duplicates.
- The recommendation service is **not on the order path**: orders never wait for it, and its outage cannot affect them.

## 5. Latency

The measurements are in [performance.md](performance.md) (recommendation section, produced by `load-tests/k6/recommendations.js`).

## 6. Tests

24 tests: the embedding (deterministic, unit length, same-companions-are-closer), the event handler (confirmed teaches, cancelled does not,
redelivery is a no-op, malformed refused), the ranking (already-bought excluded, cold start, bounded), a **real Qdrant + real Redis** pipeline
(purchases → vectors → similarity → cache, index outage catch-up, cache outage), and the security rules. The platform check proves the Kafka
path end to end: it places an order and waits until the recommendation service lists the product as purchased.

Against a **real Kafka** broker that starts empty: the service gives the order events topic its three partitions before its consumer
takes a share of it (also when the topic is already there with one), and orders on every partition are learned from at once, without
waiting for a metadata refresh.

## 7. Limitations

- Co-purchase counts are global and grow forever (no windowing); popularity is all-time. Seasonal drift is not modelled.
- 64 hashed dimensions mean occasional collisions between unrelated companions; the cosine ranking is robust to it but not exact.
  A learned embedding (matrix factorisation) would be better with real volume.
- Views and carts only feed the *customer's* affinity, not the product-to-product signal.
- No A/B framework, no offline evaluation metrics (precision@k): the quality of the recommendations is not measured, only their
  mechanics and latency.
- One collection, one replica of Qdrant; no snapshots configured beyond the persistent volume.
- The order events topic is declared once, when the service starts. If Kafka cannot be reached at that moment the service starts
  anyway (it is not on the order path) and the declaration is not repeated until the next start; on a broker that has no topic yet,
  its consumer can then again be the first to ask for it.
