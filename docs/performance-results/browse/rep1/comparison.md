# Baseline vs optimized: `browse` scenario

Baseline: {'order': 1, 'product': 1} replicas, config {'PRODUCT_CACHE_ENABLED': 'false', 'DB_POOL_SIZE': '10', 'DB_POOL_MIN_IDLE': '10', 'TOMCAT_MAX_THREADS': '200', 'TOMCAT_MAX_CONNECTIONS': '8192', 'TOMCAT_ACCEPT_COUNT': '100'}, hot indexes dropped = True.

Optimized: {'order': 2, 'product': 2} replicas, config {'PRODUCT_CACHE_ENABLED': 'true', 'DB_POOL_SIZE': '30', 'DB_POOL_MIN_IDLE': '10', 'TOMCAT_MAX_THREADS': '400', 'TOMCAT_MAX_CONNECTIONS': '16384', 'TOMCAT_ACCEPT_COUNT': '500'}.

Hold per level 45s, think time 3.0s, buy ratio 0.05. Host: 8 CPUs (shared with everything else running on the machine).

| Users | | req/s | errors % | p50 ms | p95 ms | p99 ms | browse p95 | order p95 | CPU cores | DB pool active (max) |
|---|---|---|---|---|---|---|---|---|---|---|
| 100 | baseline | 24.6 | 0 | 8 | 366 | 2262 | 401 | n/a | 2.3 | 2 |
| 100 | optimized | 23.7 | 0 | 8 | 3354 | 7338 | 3772 | n/a | 1.4 | 0 |
| 500 | baseline | 123.5 | 0 | 3 | 772 | 1785 | 780 | n/a | 1.7 | 1 |
| 500 | optimized | 128.4 | 0 | 3 | 29 | 106 | 29 | n/a | 2.1 | 0 |
| 1,000 | baseline | 241.1 | 0 | 2 | 1825 | 4415 | 1860 | n/a | 2.0 | 1 |
| 1,000 | optimized | 256.3 | 0 | 2 | 29 | 129 | 29 | n/a | 2.2 | 0 |

## Latency improvement (optimized vs baseline)

| Users | p50 | p95 | p99 | throughput x | counts towards headline |
|---|---|---|---|---|---|
| 100 | -0.6% | -817.6% | -224.4% | 0.96 | yes |
| 500 | 0.6% | 96.2% | 94.1% | 1.04 | yes |
| 1,000 | -12.5% | 98.4% | 97.1% | 1.06 | yes |

**Headline (mean over the 3 level(s) where both runs had under 1% errors):** p50 -4.2% lower, p95 -207.7% lower, p99 -11.1% lower.
