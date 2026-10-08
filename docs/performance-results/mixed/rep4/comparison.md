# Baseline vs optimized: `mixed` scenario

Baseline: {'order': 1, 'product': 1} replicas, config {'PRODUCT_CACHE_ENABLED': 'false', 'DB_POOL_SIZE': '10', 'DB_POOL_MIN_IDLE': '10', 'TOMCAT_MAX_THREADS': '200', 'TOMCAT_MAX_CONNECTIONS': '8192', 'TOMCAT_ACCEPT_COUNT': '100'}, hot indexes dropped = True.

Optimized: {'order': 2, 'product': 2} replicas, config {'PRODUCT_CACHE_ENABLED': 'true', 'DB_POOL_SIZE': '30', 'DB_POOL_MIN_IDLE': '10', 'TOMCAT_MAX_THREADS': '400', 'TOMCAT_MAX_CONNECTIONS': '16384', 'TOMCAT_ACCEPT_COUNT': '500'}.

Hold per level 45s, think time 3.0s, buy ratio 0.05. Host: 8 CPUs (shared with everything else running on the machine).

| Users | | req/s | errors % | p50 ms | p95 ms | p99 ms | browse p95 | order p95 | CPU cores | DB pool active (max) |
|---|---|---|---|---|---|---|---|---|---|---|
| 100 | baseline | 26.6 | 0 | 5 | 55 | 190 | 14 | 265 | 1.1 | 1 |
| 100 | optimized | 22.3 | 0 | 19 | 1654 | 4837 | 1269 | 8171 | 2.9 | 1 |
| 500 | baseline | 125.0 | 0 | 14 | 569 | 1038 | 452 | 2044 | 4.4 | 10 |
| 500 | optimized | 43.0 | 2.775 | 1189 | 24765 | 37770 | 25330 | 20041 | 5.6 | 19 |
| 1,000 | baseline | 110.3 | 1.47 | 1097 | 7694 | 13236 | 7538 | 15073 | 6.5 | 10 |
| 1,000 | optimized | 60.9 | 4.462 | 2800 | 41047 | 51757 | 41906 | 23497 | 5.7 | 3 |

## Latency improvement (optimized vs baseline)

| Users | p50 | p95 | p99 | throughput x | counts towards headline |
|---|---|---|---|---|---|
| 100 | -278.2% | -2921.6% | -2442.7% | 0.84 | yes |
| 500 | -8672.9% | -4248.8% | -3538.0% | 0.34 | no (errors >= 1%) |
| 1,000 | -155.4% | -433.5% | -291.0% | 0.55 | no (errors >= 1%) |

**Headline (mean over the 1 level(s) where both runs had under 1% errors):** p50 -278.2% lower, p95 -2921.6% lower, p99 -2442.7% lower.
