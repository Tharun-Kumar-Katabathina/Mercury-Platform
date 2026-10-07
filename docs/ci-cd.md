# CI/CD (Phase 17)

The pipeline is GitHub Actions (`.github/workflows`). Its principle: **the gates are the same commands a developer runs locally**, and
a failed gate blocks the merge. Nothing depends on remembering to run a check.

```
push / pull request
   │
   ├─ unit tests ×7 (one job per service, in parallel)          ./mvnw verify
   │      └─ real-stack integration tests (Order → Product → Inventory → PostgreSQL → Kafka → Notification, with failure injection)
   ├─ static checks   shellcheck · Compose files valid · Kubernetes manifests render + validate (kubeconform) · workflows valid (actionlint)
   ├─ security scan   gitleaks (secrets, full history) · Trivy (dependencies + configuration) → findings in the Security tab
   ├─ images ×7       build (layer cache) → Trivy image scan (HIGH/CRITICAL with a fix fails the build) → push to GHCR on main (tag = commit SHA)
   └─ platform        compose up → authenticated smoke test → security suite (45 attacks) → performance smoke (100 users, hard thresholds)
                                                                                      │
main only ─────────────────────────────────────────────────────────────────────────────┴─> deploy to staging (protected environment) → rollout checks → automatic rollback
nightly   ─ failure matrix (chaos) · baseline-vs-optimized load test
```

## Jobs (`ci.yml`)

| Job | What it proves | Blocks merge |
|---|---|---|
| `unit tests (<service>)` ×7 | each service's tests, including the ones that run real Kafka, Redis, Qdrant and PostgreSQL in Testcontainers | ✅ |
| `real-stack integration tests` | Order Service against real Product, Inventory, Notification, PostgreSQL and Kafka: idempotency, saga recovery, a killed process, Kafka outages, concurrency | ✅ |
| `static checks` | scripts lint clean; the four Compose combinations are valid; both Kubernetes overlays render and match the 1.31 schemas; the workflows themselves are valid | ✅ |
| `security scan` | no secret in the code *or its history*; no HIGH/CRITICAL dependency or configuration issue with a fix available | ✅ |
| `image (<service>)` ×7 | the image builds, has no HIGH/CRITICAL OS or library vulnerability with a fix; on `main` it is published tagged with the commit | ✅ |
| `platform smoke, security and performance` | the whole system works from a cold start with generated secrets; the attacks are all refused; 100 concurrent shoppers meet error-rate ≤ 1 %, browse p95 ≤ 500 ms, order p95 ≤ 2 s | ✅ |
| `deploy to staging` | `main` only, only when the repository variable `DEPLOY_ENABLED` is `true`, runs in the protected `staging` environment (required reviewers / wait timer are configured there); renders the production overlay with this commit's images, applies it, waits for every rollout, **rolls back** if any fails | n/a |

**Branch protection** (set once in the repository settings): require the status checks above on the default branch, require a pull request
and one review, disallow force-push. The pipeline cannot enforce its own gates; the repository settings do.

## Nightly (`nightly.yml`)

Two jobs too long for every pull request: the **failure matrix** (`scripts/chaos/run.py`, ~30 minutes: kills and freezes services,
partitions the network, restarts Kafka and PostgreSQL, and checks the invariants in the databases) and the **baseline-vs-optimized load test**
(`scripts/perf/run.py`). Their reports are attached to the run.

## Supply chain and housekeeping

- Actions are pinned to major versions and kept current by Dependabot (`.github/dependabot.yml`), which also updates every Maven module and
  the Docker base image. Each update is a pull request that has to pass the full pipeline.
- Least privilege: the workflow's default permission is `contents: read`; only the jobs that publish images or upload scan results get
  `packages: write` / `security-events: write`.
- Images are tagged with the commit SHA (immutable) plus `main` (moving); the production overlay is rendered with the SHA, so what runs is exactly
  what was tested.
- Concurrency: a new push cancels the previous run of the same branch's pull request; deploys never overlap and are never cancelled half-way.

## What it needs from you (once)

1. Push the repository to GitHub; enable Actions, the Security tab (code scanning) and Dependabot.
2. Protect the default branch (above).
3. For deployment: create the `staging` environment (add reviewers), add the secret `STAGING_KUBECONFIG` (base64 kubeconfig) and set the
   repository variable `DEPLOY_ENABLED=true`. Until then the deploy job is skipped, not failed.

## What was verified here, and what was not

Verified by running: every command the jobs execute (the unit and integration suites, the Compose/Kubernetes validation, the container scan
that found the Tomcat/Jackson issues, the platform check, the security suite, the performance smoke harness) and `actionlint` on the workflow files.
**Not verified:** the workflows have not run on GitHub itself (no repository connection from this environment): action versions, runner
resources and the registry push are as-written until the first real run. The Kubernetes deployment path is unproven against a live cluster
(see [infrastructure.md](infrastructure.md#7-what-was-verified-and-what-was-not)).
