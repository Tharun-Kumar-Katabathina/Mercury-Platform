# Baseline vs optimized: `mixed` scenario

Baseline: {'order': 1, 'product': 1} replicas, config {'PRODUCT_CACHE_ENABLED': 'false', 'DB_POOL_SIZE': '10', 'DB_POOL_MIN_IDLE': '10', 'TOMCAT_MAX_THREADS': '200', 'TOMCAT_MAX_CONNECTIONS': '8192', 'TOMCAT_ACCEPT_COUNT': '100'}, hot indexes dropped = True.

Optimized: {'order': 2, 'product': 2} replicas, config {'PRODUCT_CACHE_ENABLED': 'true', 'DB_POOL_SIZE': '30', 'DB_POOL_MIN_IDLE': '10', 'TOMCAT_MAX_THREADS': '400', 'TOMCAT_MAX_CONNECTIONS': '16384', 'TOMCAT_ACCEPT_COUNT': '500'}.

Hold per level 45s, think time 3.0s, buy ratio 0.05. Host: 8 CPUs (shared with everything else running on the machine).

| Users | | req/s | errors % | p50 ms | p95 ms | p99 ms | browse p95 | order p95 | CPU cores | DB pool active (max) |
|---|---|---|---|---|---|---|---|---|---|---|
| 100 | baseline | 27.1 | 0 | 4 | 42 | 101 | 11 | 185 | 0.9 | 1 |
| 100 | optimized | 26.8 | 0 | 5 | 60 | 155 | 23 | 318 | 1.2 | 0 |
| 500 | baseline | 131.3 | 0 | 2 | 21 | 48 | 6 | 82 | 1.3 | 1 |
| 500 | optimized | 129.8 | 0 | 3 | 54 | 134 | 25 | 320 | 2.3 | 2 |
| 1,000 | baseline | 259.0 | 0 | 2 | 88 | 423 | 75 | 282 | 2.8 | 1 |
| 1,000 | optimized | 195.9 | 0.735 | 138 | 5388 | 8264 | 5206 | 8993 | 6.7 | 12 |

## Latency improvement (optimized vs baseline)

| Users | p50 | p95 | p99 | throughput x | counts towards headline |
|---|---|---|---|---|---|
| 100 | -22.4% | -42.2% | -53.6% | 0.99 | yes |
| 500 | -70.8% | -157.7% | -178.0% | 0.99 | yes |
| 1,000 | -7485.7% | -6030.0% | -1854.6% | 0.76 | yes |

**Headline (mean over the 3 level(s) where both runs had under 1% errors):** p50 -2526.3% lower, p95 -2076.6% lower, p99 -695.4% lower.
