# Baseline vs optimized: `browse` scenario

Baseline: {'order': 1, 'product': 1} replicas, config {'PRODUCT_CACHE_ENABLED': 'false', 'DB_POOL_SIZE': '10', 'DB_POOL_MIN_IDLE': '10', 'TOMCAT_MAX_THREADS': '200', 'TOMCAT_MAX_CONNECTIONS': '8192', 'TOMCAT_ACCEPT_COUNT': '100'}, hot indexes dropped = True.

Optimized: {'order': 2, 'product': 2} replicas, config {'PRODUCT_CACHE_ENABLED': 'true', 'DB_POOL_SIZE': '30', 'DB_POOL_MIN_IDLE': '10', 'TOMCAT_MAX_THREADS': '400', 'TOMCAT_MAX_CONNECTIONS': '16384', 'TOMCAT_ACCEPT_COUNT': '500'}.

Hold per level 45s, think time 3.0s, buy ratio 0.05. Host: 8 CPUs (shared with everything else running on the machine).

| Users | | req/s | errors % | p50 ms | p95 ms | p99 ms | browse p95 | order p95 | CPU cores | DB pool active (max) |
|---|---|---|---|---|---|---|---|---|---|---|
| 100 | baseline | 26.8 | 0 | 4 | 16 | 51 | 16 | n/a | 0.9 | 0 |
| 100 | optimized | 25.1 | 0 | 8 | 95 | 1396 | 98 | n/a | 1.5 | 0 |
| 500 | baseline | 129.5 | 0 | 2 | 53 | 213 | 54 | n/a | 1.9 | 3 |
| 500 | optimized | 126.5 | 0 | 6 | 109 | 259 | 109 | n/a | 3.2 | 1 |
| 1,000 | baseline | 245.7 | 0 | 2 | 1236 | 3431 | 1266 | n/a | 1.7 | 0 |
| 1,000 | optimized | 80.3 | 0 | 2129 | 22005 | 25625 | 22039 | n/a | 6.0 | 12 |

## Latency improvement (optimized vs baseline)

| Users | p50 | p95 | p99 | throughput x | counts towards headline |
|---|---|---|---|---|---|
| 100 | -82.6% | -505.2% | -2653.2% | 0.94 | yes |
| 500 | -169.9% | -105.0% | -21.9% | 0.98 | yes |
| 1,000 | -121981.1% | -1681.0% | -647.0% | 0.33 | yes |

**Headline (mean over the 3 level(s) where both runs had under 1% errors):** p50 -40744.5% lower, p95 -763.7% lower, p99 -1107.3% lower.
