# Baseline vs optimized: `mixed` scenario

Baseline: {'order': 1, 'product': 1} replicas, config {'PRODUCT_CACHE_ENABLED': 'false', 'DB_POOL_SIZE': '10', 'DB_POOL_MIN_IDLE': '10', 'TOMCAT_MAX_THREADS': '200', 'TOMCAT_MAX_CONNECTIONS': '8192', 'TOMCAT_ACCEPT_COUNT': '100'}, hot indexes dropped = True.

Optimized: {'order': 2, 'product': 2} replicas, config {'PRODUCT_CACHE_ENABLED': 'true', 'DB_POOL_SIZE': '30', 'DB_POOL_MIN_IDLE': '10', 'TOMCAT_MAX_THREADS': '400', 'TOMCAT_MAX_CONNECTIONS': '16384', 'TOMCAT_ACCEPT_COUNT': '500'}.

Hold per level 45s, think time 3.0s, buy ratio 0.05. Host: 8 CPUs (shared with everything else running on the machine).

| Users | | req/s | errors % | p50 ms | p95 ms | p99 ms | browse p95 | order p95 | CPU cores | DB pool active (max) |
|---|---|---|---|---|---|---|---|---|---|---|
| 100 | baseline | 26.7 | 0 | 5 | 132 | 495 | 61 | 614 | 1.7 | 1 |
| 100 | optimized | 20.7 | 0 | 112 | 4982 | 6227 | 4767 | 7386 | 4.4 | 11 |
| 500 | baseline | 129.7 | 0 | 3 | 36 | 117 | 14 | 236 | 1.9 | 1 |
| 500 | optimized | 40.0 | 3.407 | 5451 | 16167 | 20692 | 16168 | 17568 | 5.8 | 7 |
| 1,000 | baseline | 247.3 | 0 | 4 | 1020 | 3020 | 1030 | 944 | 3.7 | 3 |
| 1,000 | optimized | 48.6 | 4.073 | 5007 | 27930 | 37597 | 28659 | 8053 | 5.9 | 11 |

## Latency improvement (optimized vs baseline)

| Users | p50 | p95 | p99 | throughput x | counts towards headline |
|---|---|---|---|---|---|
| 100 | -1950.9% | -3660.1% | -1157.5% | 0.78 | yes |
| 500 | -204701.3% | -44979.1% | -17584.4% | 0.31 | no (errors >= 1%) |
| 1,000 | -122620.6% | -2638.3% | -1144.9% | 0.20 | no (errors >= 1%) |

**Headline (mean over the 1 level(s) where both runs had under 1% errors):** p50 -1950.9% lower, p95 -3660.1% lower, p99 -1157.5% lower.
