#!/usr/bin/env python3
"""Turns the per-repetition load-test results in this folder into one summary and judges the latency-reduction claim.

  python3 docs/performance-results/aggregate.py        # writes summary.json and summary.md next to this file

Inputs (all written by scripts/perf/run.py and kept under <scenario>/rep<N>/):  <profile>-<scenario>.json
Nothing is rounded, filtered or reweighted to favour a result.  The only rule applied is the repository's own (scripts/perf/compare.py):
a user level only counts when BOTH profiles finished it with an error rate under 1 %, because a run that fails requests quickly is not "faster".

improvement % = (baseline - optimized) / baseline * 100      positive = optimized is faster, negative = optimized is slower
"""
import glob
import json
import os
import statistics as st

HERE = os.path.dirname(os.path.abspath(__file__))
TARGET = 35.0                                   # the claim under test: a 35 % latency reduction
METRICS = ("p50", "p95", "p99")
MAX_ERR = 1.0


def pct(before, after):
    return None if not before or after is None else (before - after) / before * 100


def windows():
    """label -> (power source, load average at start, slept?) for the runs the validating driver recorded"""
    out = {}
    path = os.path.join(HERE, "run-windows.jsonl")
    if os.path.exists(path):
        for line in open(path):
            d = json.loads(line)
            if d["exit"] == 0 and not d["slept_during_run"]:
                out[d["label"]] = (d["power_source"], d["load_at_start"])
    return out


def load(path):
    return {l["vus"]: l for l in json.load(open(path))["levels"]}


def main():
    win = windows()
    summary = {"target_pct": TARGET, "scenarios": {}}
    md = ["# Baseline vs optimized: summary of every valid repetition", "",
          "improvement % = (baseline - optimized) / baseline: **positive = optimized faster, negative = optimized slower**.", "",
          f"A level counts only if both profiles had an error rate under {MAX_ERR:g} %.  The claim under test is a {TARGET:g} % reduction.", ""]
    for scenario in ("mixed", "browse"):
        reps = sorted(glob.glob(os.path.join(HERE, scenario, "rep*")), key=lambda p: int(p.rsplit("rep", 1)[1]))
        if not reps:
            continue
        per_level = {}
        md += [f"## `{scenario}` scenario", "",
               "| rep | power | users | p50 base→opt ms | p95 base→opt ms | p99 base→opt ms | p50 | p95 | p99 | req/s base→opt | errors % base→opt | counts |",
               "|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for rep_dir in reps:
            rep = os.path.basename(rep_dir)
            b = load(os.path.join(rep_dir, f"baseline-{scenario}.json"))
            o = load(os.path.join(rep_dir, f"optimized-{scenario}.json"))
            power = win.get(f"{scenario}-{rep}-baseline", ("not recorded",))[0]
            for vus in sorted(set(b) & set(o)):
                bl, ol = b[vus], o[vus]
                ok = bl["error_rate_pct"] < MAX_ERR and ol["error_rate_pct"] < MAX_ERR
                imp = {m: pct(bl["latency_ms"][m], ol["latency_ms"][m]) for m in METRICS}
                imp_scoped = {"browse_p95": pct(bl["browse_ms"]["p95"], ol["browse_ms"]["p95"]),
                              "order_p95": pct(bl["order_ms"]["p95"], ol["order_ms"]["p95"])}
                per_level.setdefault(vus, []).append({"rep": rep, "counts": ok, "improvement": imp, "scoped": imp_scoped,
                                                      "base": bl["latency_ms"], "opt": ol["latency_ms"]})
                md.append(f"| {rep} | {power} | {vus:,} | " + " | ".join(f"{bl['latency_ms'][m]:.0f}→{ol['latency_ms'][m]:.0f}" for m in METRICS) + " | "
                          + " | ".join(f"{imp[m]:+.1f}%" for m in METRICS)
                          + f" | {bl['rps']}→{ol['rps']} | {bl['error_rate_pct']}→{ol['error_rate_pct']} | {'yes' if ok else 'no (errors)'} |")
        md += ["", f"### `{scenario}`: improvement across repetitions (only levels with errors under {MAX_ERR:g} % in both profiles)", "",
               f"| users | metric | valid reps | median | min | max | reps reaching {TARGET:g} % | verdict |", "|---|---|---|---|---|---|---|---|"]
        sc_summary = {}
        for vus in sorted(per_level):
            sc_summary[vus] = {}
            for m in METRICS:
                vals = [r["improvement"][m] for r in per_level[vus] if r["counts"] and r["improvement"][m] is not None]
                hit = sum(v >= TARGET for v in vals)
                if not vals:
                    verdict = "no valid repetition (errors >= 1 %)"
                elif hit == len(vals):
                    verdict = f"reached in all {len(vals)}"
                elif hit == 0:
                    verdict = "never reached" + (" (optimized slower)" if st.median(vals) < 0 else "")
                else:
                    verdict = f"inconsistent ({hit} of {len(vals)})"
                sc_summary[vus][m] = {"reps": len(vals), "median": st.median(vals) if vals else None,
                                      "min": min(vals) if vals else None, "max": max(vals) if vals else None,
                                      "reps_reaching_target": hit, "verdict": verdict}
                if vals:
                    md.append(f"| {vus:,} | {m} | {len(vals)} | {st.median(vals):+.1f}% | {min(vals):+.1f}% | {max(vals):+.1f}% | {hit} of {len(vals)} | {verdict} |")
                else:
                    md.append(f"| {vus:,} | {m} | 0 | n/a | n/a | n/a | n/a | {verdict} |")
        # the repository's own headline: mean over the levels that count, per repetition
        heads = []
        for rep_dir in reps:
            rep = os.path.basename(rep_dir)
            vals = {m: [r["improvement"][m] for lv in per_level.values() for r in lv if r["rep"] == rep and r["counts"]] for m in METRICS}
            if all(vals[m] for m in METRICS):
                heads.append({"rep": rep, **{m: sum(vals[m]) / len(vals[m]) for m in METRICS}})
        md += ["", f"Repository headline (compare.py: mean over the counting levels), per repetition: "
               + ("; ".join(f"{h['rep']}: p50 {h['p50']:+.0f}%, p95 {h['p95']:+.0f}%, p99 {h['p99']:+.0f}%" for h in heads) or "none"), ""]
        summary["scenarios"][scenario] = {"per_level": sc_summary, "headline_per_rep": heads}
    json.dump(summary, open(os.path.join(HERE, "summary.json"), "w"), indent=1)
    open(os.path.join(HERE, "summary.md"), "w").write("\n".join(md).rstrip("\n") + "\n")
    print("\n".join(md))


if __name__ == "__main__":
    main()
