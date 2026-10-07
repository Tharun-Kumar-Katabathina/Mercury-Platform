#!/usr/bin/env python3
"""Compares baseline and optimized load-test results and writes docs/performance-results/comparison.md.

The latency improvement is computed per user level and summarised honestly: only levels where BOTH runs completed
with an error rate under 1% count towards the headline number, because a run that fails requests quickly is not
"faster".
"""
import json
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "docs", "performance-results")


def load(profile, scenario):
    path = os.path.join(RES, f"{profile}-{scenario}.json")
    return json.load(open(path)) if os.path.exists(path) else None


def pct(before, after):
    return None if not before or after is None else (before - after) / before * 100


def fmt(x, nd=0):
    return "n/a" if x is None else f"{x:.{nd}f}"


def main(scenario="mixed"):
    base, opt = load("baseline", scenario), load("optimized", scenario)
    if not base or not opt:
        print("need both baseline and optimized results")
        return 1
    bl = {l["vus"]: l for l in base["levels"]}
    ol = {l["vus"]: l for l in opt["levels"]}
    rows, valid = [], []
    lines = [f"# Baseline vs optimized: `{scenario}` scenario", "",
             f"Baseline: {base['replicas']} replicas, config {base['config']}, hot indexes dropped = {base['indexes_dropped']}.  ",
             f"Optimized: {opt['replicas']} replicas, config {opt['config']}.  ",
             f"Hold per level {base['hold']}, think time {base['think_seconds']}s, buy ratio {base['buy_ratio']}. "
             f"Host: {base['host']['cpus']} CPUs (shared with everything else running on the machine).", "",
             "| Users | | req/s | errors % | p50 ms | p95 ms | p99 ms | browse p95 | order p95 | CPU cores | DB pool active (max) |",
             "|---|---|---|---|---|---|---|---|---|---|---|"]
    for vus in sorted(set(bl) | set(ol)):
        for label, d in (("baseline", bl.get(vus)), ("optimized", ol.get(vus))):
            if not d:
                continue
            L, S = d["latency_ms"], d["system"]
            lines.append(f"| {vus:,} | {label} | {d['rps']} | {d['error_rate_pct']} | {fmt(L['p50'])} | {fmt(L['p95'])} | {fmt(L['p99'])} | "
                         f"{fmt(d['browse_ms']['p95'])} | {fmt(d['order_ms']['p95'])} | {fmt(S['docker_cpu_cores'], 1)} | {fmt(S['db_pool_active_max'])} |")
        b, o = bl.get(vus), ol.get(vus)
        if b and o:
            ok = b["error_rate_pct"] < 1 and o["error_rate_pct"] < 1
            imp = {k: pct(b["latency_ms"][k], o["latency_ms"][k]) for k in ("p50", "p95", "p99")}
            rows.append((vus, imp, ok, o["rps"] / b["rps"] if b["rps"] else None))
            if ok:
                valid.append(imp)
    lines += ["", "## Latency improvement (optimized vs baseline)", "",
              "| Users | p50 | p95 | p99 | throughput x | counts towards headline |", "|---|---|---|---|---|---|"]
    for vus, imp, ok, tput in rows:
        lines.append(f"| {vus:,} | {fmt(imp['p50'], 1)}% | {fmt(imp['p95'], 1)}% | {fmt(imp['p99'], 1)}% | {fmt(tput, 2)} | {'yes' if ok else 'no (errors >= 1%)'} |")
    if valid:
        avg = {k: sum(v[k] for v in valid) / len(valid) for k in ("p50", "p95", "p99")}
        lines += ["", f"**Headline (mean over the {len(valid)} level(s) where both runs had under 1% errors):** "
                      f"p50 {avg['p50']:.1f}% lower, p95 {avg['p95']:.1f}% lower, p99 {avg['p99']:.1f}% lower."]
    else:
        lines += ["", "**No level had under 1% errors in both runs, so no honest latency-improvement figure can be given.**"]
    open(os.path.join(RES, "comparison.md"), "w").write("\n".join(lines) + "\n")
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main(*(sys.argv[1:2] or ["mixed"])))
