#!/usr/bin/env python3
"""Reads per-service metrics from the running load-test stack's Prometheus (localhost:9090) for the window that just ended.

Usage: collect.py <label> <window-seconds>   -> writes diagnostics/<label>.json

The load-test harness records one number per metric across all services (for example "max connection-pool pending").
This attributes those numbers to a service, so a difference between two runs can be explained instead of guessed at.
"""
import json
import os
import sys
import urllib.parse
import urllib.request

PROM = "http://localhost:9090"
label, window = sys.argv[1], int(sys.argv[2])
w = f"{window}s"
QUERIES = {
    "pool_active_max_by_service": f"max by (application) (max_over_time(hikaricp_connections_active[{w}]))",
    "pool_pending_max_by_service": f"max by (application) (max_over_time(hikaricp_connections_pending[{w}]))",
    "pool_size_by_service": "max by (application) (hikaricp_connections_max)",
    "pool_timeouts_by_service": f"sum by (application) (increase(hikaricp_connections_timeout_total[{w}]))",
    "cpu_fraction_by_service": f"avg by (application) (avg_over_time(process_cpu_usage[{w}]))",
    "http_p95_seconds_by_service": f'histogram_quantile(0.95, sum by (application, le) (rate(http_server_requests_seconds_bucket{{uri=~"/api/.*"}}[{w}])))',
    "http_requests_per_second_by_service": f'sum by (application) (rate(http_server_requests_seconds_count{{uri=~"/api/.*"}}[{w}]))',
    "http_5xx_by_service": f'sum by (application) (increase(http_server_requests_seconds_count{{status=~"5.."}}[{w}]))',
    "product_cache_by_result": f"sum by (result) (increase(product_cache_total[{w}]))",
    "container_memory_mb": 'max by (name) (container_memory_working_set_bytes{name!=""}) / 1e6',
    "container_memory_limit_mb": 'max by (name) (container_spec_memory_limit_bytes{name!=""}) / 1e6',
    "container_cpu_cores": f'sum by (name) (rate(container_cpu_usage_seconds_total{{name!=""}}[{w}]))',
    "kafka_consumer_lag": "sum(kafka_consumergroup_lag)",
}
out = {"label": label, "window_seconds": window}
for name, q in QUERIES.items():
    url = f"{PROM}/api/v1/query?" + urllib.parse.urlencode({"query": q})
    try:
        data = json.load(urllib.request.urlopen(url, timeout=30))["data"]["result"]
    except Exception as e:                                   # a missing metric is data too: record it, do not hide it
        out[name] = f"query failed: {e}"
        continue
    rows = {}
    for r in data:
        key = r["metric"].get("application") or r["metric"].get("name") or r["metric"].get("result") or "all"
        v = float(r["value"][1])
        rows[key] = None if v != v else round(v, 4)
    out[name] = rows
path = os.path.join(os.path.dirname(os.path.abspath(__file__)), f"{label}.json")
json.dump(out, open(path, "w"), indent=1)
print("wrote", path)
