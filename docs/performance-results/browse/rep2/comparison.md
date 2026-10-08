# Baseline vs optimized: `browse` scenario

Baseline: {'order': 1, 'product': 1} replicas, config {'PRODUCT_CACHE_ENABLED': 'false', 'DB_POOL_SIZE': '10', 'DB_POOL_MIN_IDLE': '10', 'TOMCAT_MAX_THREADS': '200', 'TOMCAT_MAX_CONNECTIONS': '8192', 'TOMCAT_ACCEPT_COUNT': '100'}, hot indexes dropped = True.

Optimized: {'order': 2, 'product': 2} replicas, config {'PRODUCT_CACHE_ENABLED': 'true', 'DB_POOL_SIZE': '30', 'DB_POOL_MIN_IDLE': '10', 'TOMCAT_MAX_THREADS': '400', 'TOMCAT_MAX_CONNECTIONS': '16384', 'TOMCAT_ACCEPT_COUNT': '500'}.

Hold per level 45s, think time 3.0s, buy ratio 0.05. Host: 8 CPUs (shared with everything else running on the machine).

| Users | | req/s | errors % | p50 ms | p95 ms | p99 ms | browse p95 | order p95 | CPU cores | DB pool active (max) |
|---|---|---|---|---|---|---|---|---|---|---|
| 100 | baseline | 25.9 | 0 | 4 | 12 | 31 | 11 | n/a | 0.8 | 1 |
| 100 | optimized | 26.5 | 0 | 7 | 31 | 74 | 30 | n/a | 1.2 | 0 |
| 500 | baseline | 129.9 | 0 | 2 | 161 | 593 | 165 | n/a | 0.9 | 1 |
| 500 | optimized | 112.0 | 0 | 3 | 4001 | 7602 | 4046 | n/a | 3.9 | 5 |
| 1,000 | baseline | 257.3 | 0 | 2 | 12 | 263 | 12 | n/a | 1.4 | 0 |
| 1,000 | optimized | 244.9 | 0 | 3 | 267 | 1849 | 270 | n/a | 3.5 | 1 |

## Latency improvement (optimized vs baseline)

| Users | p50 | p95 | p99 | throughput x | counts towards headline |
|---|---|---|---|---|---|
| 100 | -56.9% | -151.9% | -140.2% | 1.02 | yes |
| 500 | -78.2% | -2384.4% | -1182.8% | 0.86 | yes |
| 1,000 | -83.4% | -2187.7% | -602.5% | 0.95 | yes |

**Headline (mean over the 3 level(s) where both runs had under 1% errors):** p50 -72.8% lower, p95 -1574.7% lower, p99 -641.8% lower.
