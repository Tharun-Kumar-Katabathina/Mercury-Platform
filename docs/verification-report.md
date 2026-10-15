# Phase 17.5 verification report

**Scope.** Verify that what Phases 1–17 built works end to end. Defects found were fixed, and each fix came with a regression test that
fails without it. No new features. Phase 18 (AWS / Terraform, a live cluster) was out of scope and on hold.

**State verified.** `master` at `a20be8b` (2026-10-15), after pull requests #25–#35.

**Method.** Every claim below is backed by something that was run: a unit or real-stack test in CI, a chaos scenario on a freshly started
stack, or the platform smoke, security and performance gates. A claim that could not be run is listed under
[what was not verified](#4-what-was-not-verified). GitHub Actions is the test machine for everything that needs Docker.

## 1. Verdict

| Area | Result |
|---|---|
| Functional correctness (Phases 1–10) | **Verified.** 538 tests across 7 services, plus the real-stack suite, green on `master` |
| Failure behaviour (Phase 13) | **Verified.** All 19 chaos scenarios green in the full nightly on `master`<sup>[1]</sup>; the scenarios that were flaky or wrong were fixed and repeated ×3 to ×10 |
| Infrastructure (Phase 11) | **Partly verified.** Images, Compose stack and Kubernetes manifests (rendered and schema-checked) yes; a live cluster no |
| Observability (Phase 12) | **Verified locally** as documented; alerts are evaluated but not delivered anywhere |
| Performance (Phase 14) | **100-user gate passes in CI.** The claimed 35 % latency reduction (optimized vs baseline) is **not proven, and contradicted** by the measurements |
| Security (Phase 15) | **Verified.** 46-attack suite through the gateway on every CI run; dependency, secret, configuration and image scans gate the build |
| Recommendations (Phase 16) | **Mechanics verified**, one lost-update defect fixed; recommendation quality is not measured |
| CI/CD (Phase 17) | **CI and continuous delivery verified**: every green merge publishes tested images. **Deployment not verified**: no cluster |

<sup>[1]</sup> Full nightly on `a20be8b` (2026-10-15): all 19 scenarios passed and baseline-vs-optimized completed — https://github.com/Tharun-Kumar-Katabathina/Mercury-Platform/actions/runs/38696401719

## 2. What each phase delivers and how it was verified

| Phase | What it delivers | Evidence |
|---|---|---|
| 1–5 | Product and Inventory services, stock reservation with idempotency keys | Product 50 tests, Inventory 126 tests |
| 6 | Product → Inventory reservation over REST | `ProductInventoryIntegrationTests` against real PostgreSQL and a real Inventory process |
| 7 | Order Service: idempotent order saga with compensation | `OrderServiceTests` (17, including 100-way concurrency), `OrderFlowIntegrationTests` (12, real stack) |
| 8 | Durable saga recovery, ambiguous reservations, circuit breaker | `SagaRecoveryTests` (12), `SagaRecoveryIntegrationTests` (8, real stack), chaos F01–F10 |
| 9 | Kafka events through a transactional outbox, idempotent consumers, dead letters | `EventFlowIntegrationTests`, `OrderEventsTests`, chaos F04–F06, F17 |
| 10 | ASYNC order-level reservation over Kafka, deadline recovery | `AsyncReservationIntegrationTests` (9) and `AsyncFailureInjectionIntegrationTests` (4), chaos F11–F17 |
| 11 | Container images, production-like Compose stack, Kubernetes overlays | CI image builds and scans, platform job (`compose up`), kubeconform on both overlays |
| 12 | Metrics, dashboards, traces across REST and Kafka, log correlation | `docs/observability.md` §7; 18 alert rules validated with `promtool` |
| 13 | Failure matrix (19 scenarios) and invariant checks | Nightly failure matrix; targeted ×3 / ×10 runs listed in section 3 |
| 14 | Load tests, baseline vs optimized | `docs/performance-results/` (every run kept) and the 100-user CI gate |
| 15 | JWT and roles, API gateway, secrets, scans | `SecurityApiTests` in the five resource services, the gateway's security and rate-limit tests, `AuthApiTests` and `LoginRateLimitTests` (User), 46-attack suite, gitleaks, Trivy |
| 16 | Purchase features, vector search, personalised results | Recommendation 43 tests, including a pipeline against real Qdrant and Redis and tests against a real Kafka broker |
| 17 | CI/CD pipeline | 8-minute CI with change detection; images pushed and promoted on `master` (first release: CI run on `09f537b`) |

Test counts are `@Test` / `@ParameterizedTest` methods in the current sources: api-gateway 54, inventory-service 126,
notification-service 20, order-service 229, product-service 50, recommendation-service 43, user-service 16 (**538**).

## 3. Defects found and fixed during Phase 17.5

Every fix below came with a test that fails without it, or, for chaos scenarios, with repeated runs on a fresh stack.

### Correctness

| Defect | Effect | Fix |
|---|---|---|
| A reserve still in flight when Order cancelled could be applied afterwards (SYNC) | Stock held for a cancelled order for ever | Per-key **fence** at Inventory before cancelling (#25) |
| The same race on the ASYNC path | Stock held until a late reply was consumed; for ever if it never was | Order-level fence at the deadline (#32) |
| Fence key longer than 255 characters | 500 from the database | 400 `VALIDATION_FAILED` on all four endpoints (#30) |
| Fence tombstones never deleted | Unbounded table growth | Hourly cleanup, 7-day retention (#31) |
| `createdAt` / `updatedAt` stamped in nanoseconds, stored in microseconds | The value returned on create differed from every later read | Stamps cut to microseconds: Product (`67a8b8a`), Order, Saga, idempotency record, Inventory, User (#28), order reservation (#32) |
| Recommendation index sync cleared the "dirty" flag unconditionally | A purchase during a push was lost from the index | Compare-and-clear on a purchase counter (#29) |
| Vector collection setup and index sync raced when callers overlapped | Failures or duplicate work at start-up | `19703ac`, `5c7986b` |
| A failure inside a service reached the caller as 401/403 | Wrong status for real errors | `3948afe` |
| Gateway: requests without a body, chunked bodies over the size limit | Broken forwarding; the size cap was bypassable | `94ca274`, `b0881e1` |
| User Service could not read the JWT private key on Linux | Logins failed on Linux hosts | `f103b83` |
| lz4-java with a known vulnerability bundled by Kafka | Failing security gate | Overridden to 1.11.4 (`d4e3652`) |

### Tests, chaos harness and CI

| Defect | Fix |
|---|---|
| F04 failed 5/5 on F01–F04 runs: F03's failures were still in the product breaker's window | F03 waits for them to age out; breaker transitions logged (#26) |
| F03 failed once after Product's restart: the breaker was still open | F03 waits for the open interval (#26, F01–F04 ×10 green) |
| F15 asserted the pre-fence late reservation | Asserts the fenced behaviour (#32, F11–F17 ×3 green) |
| Order-dependent or timing-dependent tests (outbox backoff, similarity, index sync, start-up syncs, Kafka listener restart) | `410cd2d`, `1001f0c`, `c4e6198`, `49b41cf`, `152f212`, `cccff37` |
| Chaos harness depended on a cold start and the breaker window | `7b7ed19` |
| CI broke when the repository became private (gitleaks permissions, Security tab upload) | #27 |
| Actions cache over its 10 GB limit | Only `master` writes the layer cache, plus a weekly cleanup (#27) |
| CI took about 20 minutes for any change | Change detection, and the platform job beside the real-stack tests: 8 minutes for the full suite (#33) |
| Publishing and deploying were written for `main`; the branch is `master` | Default branch read from the repository; build once, promote after every gate; Inventory deployed before Order (#34) |
| Stale test counts and a commit citation not in the history | #35 and this change (Order/Product/Inventory/Notification totals, Recommendation 43, `SecurityApiTests` 40) |
| Six links to a `docs/performance.md` that never existed, some describing results that were never produced (10,000 users, a recommendation latency section, the token-verification overhead) | This change: links point to `docs/performance-results/README.md`, and the documents now say these were not measured |

## 4. What was not verified

- **A live Kubernetes cluster.** The manifests are rendered and schema-checked (58 resources), but they have never been applied. That
  leaves several things untested:
  - HPA behaviour;
  - NetworkPolicy enforcement;
  - the deploy job's ordered rollout and its automatic rollback.

  Running these needs a cluster, which is the Phase 18 work and is on hold.
- **Staging deployment.** The job exists, but it needs the `STAGING_KUBECONFIG` secret, the `staging` environment and `DEPLOY_ENABLED=true`.
- **Terraform** (`infrastructure/terraform`): not applied (Phase 18).
- **The 35 % performance claim**: not proven. See `docs/performance-results/README.md`. The measurements ran on a laptop with the load
  generator on the same machine. The optimized profile was slower in the mixed scenario in all 4 repetitions, and the browse results did not
  reproduce. The 100-user CI gate passes, and it is the performance guarantee that holds.
- **Recommendation quality.** No offline metric such as precision@k exists, so only the mechanics and latency are tested.
- **Alert delivery.** Alert rules are evaluated, but there is no Alertmanager or pager.

## 5. Known limitations, unchanged by design

These are documented in the phase documents and accepted for this stage. None is a correctness defect.

- **Infrastructure:**
  - single-node Kafka and single-instance PostgreSQL and Qdrant;
  - no TLS inside the network;
  - Kafka has no authentication or ACLs (`security.md` §9, `event-driven-architecture.md` §13).
- **Security:**
  - no token revocation, refresh tokens or MFA;
  - key rotation is manual;
  - the rate limiter is per instance (`security.md` §9).
- **Data growth:**
  - outbox rows, processed-event markers and `order_reservations` decisions are never cleaned up (fence tombstones are, since #31);
  - dead letters have no replay tooling (`async-reservation.md` §11).
- **Breaker behaviour:** after an outage with little traffic, a recovered service can be refused for up to the breaker window, as
  configured (`saga-recovery.md`).

## 6. Open items

| Item | Owner / next step |
|---|---|
| Staging cluster and deployment settings | Repository owner; Phase 18 |
| 23 Dependabot pull requests (Spring Boot 4.1.1, Maven 3.10.0, lz4, resilience4j, Actions major versions) | One group at a time through CI |
| Node 20 deprecation warnings on several Actions | Resolved by the Actions bumps above |
| Branch protection on `master` | Repository settings: require a pull request and the always-running checks |

## 7. How to reproduce

- **Unit and real-stack tests.** CI runs on every pull request; `docs/local-development.md` has the local commands.
- **Failure matrix.** Run *Nightly resilience and performance* from the Actions tab. The `scenarios` input (for example `F03 F04`) and
  the `repeat` input run a subset several times on fresh stacks.
- **Performance.** Run `python3 scripts/perf/run.py` as described in `docs/performance-results/README.md`.
