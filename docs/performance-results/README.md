# Phase 17.5: performance verification evidence

Everything here was produced on 2026-10-08 on one laptop, from commit `2f6307b` plus the `scripts/perf/smoke.sh` retry fix committed with this folder.
No number below was rounded, filtered or reweighted. Where the data contradicts the claim, it says so.

## Verdict

| Question | Answer |
|---|---|
| Was a **35 % latency reduction** (optimized vs baseline) proven? | **NOT PROVEN, and contradicted.** |
| Default `mixed` scenario (browse + order) | Optimized was **slower in every one of 4 repetitions at every user level**. Repository headline per repetition (p95, mean over counting levels): rep1 -2077 %, rep2 -4136 %, rep3 -3660 %, rep4 -2922 %. 0 of 4 repetitions reached 35 % at any level. |
| `browse` (read-only, cache-friendly) scenario | One of three repetitions showed a large win at 500 and 1,000 users (rep1, p95 +96.2 % / +98.4 %). The other two repetitions, run the same way on AC power, showed the optimized profile slower (p95 -2384 % / -2188 % in rep2, -105 % / -1681 % in rep3). **Not reproducible, so not proven.** |
| How much reduction was actually obtained? | None that can be relied on. The only positive results are the three browse rep1 cells above; no user level has a positive median across repetitions, so the reproducible reduction is **none** (0 %). |
| 100-user CI gate | **2 of 3 runs pass; run 1 fails** (browse p95 581 ms > 500 ms). Error rate 0 % in all three. See below. |

The claim is also not defined anywhere in the repository (no document says what is 35 % lower than what), so it was tested the way `scripts/perf/compare.py` compares profiles: per user level, p50 / p95 / p99, `(baseline - optimized) / baseline`, counting a level only when both profiles had an error rate under 1 %.

## What was measured

`python3 scripts/perf/run.py --profile {baseline,optimized} --scenario {mixed,browse} --steps 100,500,1000` (nginx load balancer in front of the gateway, security disabled for the load run; defaults for ramp / hold / think time).

* **baseline**: 1 replica of order and product, no product cache, DB pool 10, the hot indexes dropped.
* **optimized**: Redis product cache, DB pool 30, Tomcat 400 threads, 2 replicas of order and product, indexes present.

`aggregate.py` turns the per-repetition files into `summary.md` / `summary.json` (every row, every repetition). `diagnostics/collect.py` reads Prometheus for the diagnostic runs.

## Environment (`environment.json`)

macOS 26.6.2, Apple silicon, 8 CPUs, 8 GB RAM; Docker 28.2.2 with 8 CPUs and **3.83 GB**; k6 and the monitoring stack run on the same machine as the system under test.

## Every run

All runs below are valid: none spanned a system sleep (`run-windows.jsonl`: start/end epoch, power source, load average at start, `slept_during_run`, uptime-clock offset change). Sleep is detected by a jump of the uptime-clock offset above 3 s or a change of `kern.waketime`; a run that slept is discarded and repeated.

| Run | Where | Power | Result |
|---|---|---|---|
| CI-style 100-user smoke gate x3 | `gate/` | not recorded (before the sleep, 02:37-02:40) | run 1 FAIL (browse p95 581 ms, order p95 1060 ms), run 2 PASS (120 / 669 ms), run 3 PASS (156 / 632 ms) |
| mixed rep1 | `mixed/rep1` | not recorded (02:41-02:51, finished before the first sleep) | optimized slower |
| mixed rep2, rep3 | `mixed/rep2`, `rep3` | **battery** | optimized slower; rep3 optimized had 3.4 % and 4.1 % errors at 500 / 1000 users |
| mixed rep4 | `mixed/rep4` | AC | optimized slower; 500 and 1000 users above 1 % errors in the optimized profile (2.8 %, 4.5 %), baseline also 1.5 % at 1000 users |
| browse rep1, rep2, rep3 | `browse/rep1-3` | AC | rep1 win at 500 / 1000 users; rep2 and rep3 optimized slower |
| diagnostic, baseline and optimized, mixed 1000 users | `diagnostics/` | AC | see below |
| optimized Redis evidence run | `diagnostics/optimized-redis-evidence.txt` | AC | see below |

`run-windows.jsonl` also holds two `diag-*` attempt-1 rows with exit 1: my own driver script had a shell-variable shadowing bug and exited instantly with no load. They produced no data and were rerun (the later `diag-*` rows are the valid ones).

### Why the first chain was discarded

The machine went to clamshell sleep on battery at 02:53:13 and stayed asleep for hours (`pmset -g log`), which silently stretched wall-clock timings. The gate runs and mixed rep1 had finished before that and were kept. Everything started after it was discarded and rerun, with a sleep detector and (for the later repetitions) a plugged-in precondition. Mixed rep2 and rep3 ran on battery before the precondition existed; they are kept and labelled, and rep4 repeats mixed on AC with the same conclusion.

## 100-user gate and the `smoke.sh` defect

`scripts/perf/smoke.sh` logged in with `curl -sf`. Straight after the security suite's brute-force burst the gateway's rate limiter answered **429 RATE_LIMITED**, so the gate died with exit 22 before sending any load (`gate/defect-login-429-after-security-suite.txt`). Fix (kept, in this commit): retry the login up to 30 times, 2 s apart, only on 429; any other status fails immediately, with the status in the message. Thresholds are unchanged. Proven fail-before / pass-after, shellcheck clean.

The gate itself is **marginal on this laptop**, not comfortably green: browse p95 was 581 ms in run 1 (limit 500) while the host was busy, and 120 / 156 ms in runs 2 and 3. Order p95 was 1060 / 669 / 632 ms against a 2000 ms limit; error rate 0 % in all three. Reporting it as a 2-of-3 pass, not as a pass.

## Results by scenario

Full tables are in `summary.md`. Headline (positive = optimized faster):

**mixed**, p95 baseline -> optimized, ms:

| users | rep1 | rep2 (battery) | rep3 (battery) | rep4 (AC) |
|---|---|---|---|---|
| 100 | 42 -> 60 | 42 -> 144 | 132 -> 4982 | 55 -> 1654 |
| 500 | 21 -> 54 | 57 -> 6008 | 36 -> 16167 (3.4 % err) | 569 -> 24765 (2.8 % err) |
| 1,000 | 88 -> 5388 | 1070 -> 19944 | 1020 -> 27930 (4.1 % err) | 7694 -> 41047 (baseline 1.5 %, optimized 4.5 % err) |

**browse**, p95 baseline -> optimized, ms:

| users | rep1 | rep2 | rep3 |
|---|---|---|---|
| 100 | 366 -> 3354 | 12 -> 31 | 16 -> 95 |
| 500 | 772 -> 29 (+96.2 %) | 161 -> 4001 | 53 -> 109 |
| 1,000 | 1825 -> 29 (+98.4 %) | 12 -> 267 | 1236 -> 22005 |

Baseline p95 itself varies a lot between repetitions (browse 1000 users: 1825, 12, 1236 ms), so single-run comparisons on this host are not trustworthy; that is the reason the verdict uses all repetitions.

## Diagnostics (`diagnostics/`, mixed, 1000 users, live Prometheus metrics)

* **Baseline**: bottleneck is the product-service DB pool (size 10; pending requests peaked at 184); order p95 5.3 s, 5xx on order-service 16.8.
* **Optimized**: the pools are no longer the problem (size 30, 0 pending, 0 timeouts), but the cache path is failing: `product_cache_total` error 5765.6 vs hit 928.1 vs miss 171.8; product-service p95 26.2 s; order-service 5xx 176.5.
* `diagnostics/optimized-redis-evidence.txt`: product-service logged `RedisConnectionFailureException: Unable to conn...` from `ProductCache` while Lettuce was reconnecting to `redis:6379`; Redis itself was healthy afterwards (0 rejected connections, 0 evictions, 1.28 MB of 96 MB used, slowlog only exporter calls). The exception's full message was not captured, and **why the client connections fail under load was not established**.

So the data say the optimized profile does not deliver its promised reduction here because the cache layer degrades under load on this setup, not because the idea of caching is wrong. That is an observation, not a proof of cause.

## Limitations

* One 8 GB laptop; the Docker VM has 3.83 GB for ~25 containers; load generator and observability share the machine. Absolute numbers say little about real hardware.
* Only 100 / 500 / 1,000 users were run. No 2,500 / 5,000 / 10,000-user level was attempted.
* Mixed rep2 and rep3 ran on battery, mixed rep1 and the gate have no recorded power source; the later AC repetitions agree with them.
* The product cache failure was not root-caused (see above), and fixing it was out of scope for this checkpoint (verification only, no application changes).
* The repository `README.md` links to `docs/performance.md`, which does not exist; it was left alone because it is outside this checkpoint's scope.

## Reproducing

```bash
python3 scripts/perf/run.py --profile baseline  --scenario mixed --steps 100,500,1000
python3 scripts/perf/run.py --profile optimized --scenario mixed --steps 100,500,1000
python3 scripts/perf/compare.py                       # per-run comparison
python3 docs/performance-results/aggregate.py          # across repetitions
```

Plug in the machine and keep it awake: a sleep during a run invalidates it.
