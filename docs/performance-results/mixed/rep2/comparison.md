# Baseline vs optimized: `mixed` scenario

Baseline: {'order': 1, 'product': 1} replicas, config {'PRODUCT_CACHE_ENABLED': 'false', 'DB_POOL_SIZE': '10', 'DB_POOL_MIN_IDLE': '10', 'TOMCAT_MAX_THREADS': '200', 'TOMCAT_MAX_CONNECTIONS': '8192', 'TOMCAT_ACCEPT_COUNT': '100'}, hot indexes dropped = True.

Optimized: {'order': 2, 'product': 2} replicas, config {'PRODUCT_CACHE_ENABLED': 'true', 'DB_POOL_SIZE': '30', 'DB_POOL_MIN_IDLE': '10', 'TOMCAT_MAX_THREADS': '400', 'TOMCAT_MAX_CONNECTIONS': '16384', 'TOMCAT_ACCEPT_COUNT': '500'}.

Hold per level 45s, think time 3.0s, buy ratio 0.05. Host: 8 CPUs (shared with everything else running on the machine).

| Users | | req/s | errors % | p50 ms | p95 ms | p99 ms | browse p95 | order p95 | CPU cores | DB pool active (max) |
|---|---|---|---|---|---|---|---|---|---|---|
| 100 | baseline | 27.0 | 0 | 4 | 42 | 123 | 11 | 166 | 0.8 | 1 |
| 100 | optimized | 26.3 | 0 | 8 | 144 | 475 | 68 | 1351 | 2.4 | 1 |
| 500 | baseline | 130.1 | 0 | 2 | 57 | 552 | 24 | 310 | 1.6 | 2 |
| 500 | optimized | 92.8 | 0.405 | 56 | 6008 | 11437 | 5449 | 12637 | 6.0 | 11 |
| 1,000 | baseline | 250.5 | 0 | 2 | 1070 | 3002 | 1068 | 1150 | 2.5 | 1 |
| 1,000 | optimized | 97.0 | 0.331 | 1069 | 19944 | 25342 | 20054 | 7799 | 6.1 | 6 |

## Latency improvement (optimized vs baseline)

| Users | p50 | p95 | p99 | throughput x | counts towards headline |
|---|---|---|---|---|---|
| 100 | -108.8% | -246.8% | -287.3% | 0.97 | yes |
| 500 | -2470.5% | -10396.7% | -1970.4% | 0.71 | yes |
| 1,000 | -55121.9% | -1764.1% | -744.2% | 0.39 | yes |

**Headline (mean over the 3 level(s) where both runs had under 1% errors):** p50 -19233.7% lower, p95 -4135.8% lower, p99 -1000.6% lower.
