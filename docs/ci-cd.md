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
   ├─ images ×7       build (layer cache) → Trivy image scan (HIGH/CRITICAL with a fix fails the build) → on the default branch: push to GHCR (tag = commit SHA)
   └─ platform        compose up → authenticated smoke test → security suite (46 attacks) → performance smoke (100 users, hard thresholds)
                                                                                      │
default branch, every gate green ─> release: tag the tested images "latest" ──────────┴─> deploy to staging (protected environment, when enabled):
                                                                                           Inventory and the rest first, then Order → rollout checks → automatic rollback
nightly   ─ failure matrix (chaos) · baseline-vs-optimized load test
```

## Jobs (`ci.yml`)

| Job | What it proves | Blocks merge |
|---|---|---|
| `unit tests (<service>)` ×7 | each service's tests, including the ones that run real Kafka, Redis, Qdrant and PostgreSQL in Testcontainers | ✅ |
| `real-stack integration tests` | Order Service against real Product, Inventory, Notification, PostgreSQL and Kafka: idempotency, saga recovery, a killed process, Kafka outages, concurrency | ✅ |
| `static checks` | scripts lint clean; the four Compose combinations are valid; both Kubernetes overlays render and match the 1.31 schemas; the workflows themselves are valid | ✅ |
| `security scan` | no secret in the code *or its history*; no HIGH/CRITICAL dependency or configuration issue with a fix available | ✅ |
| `image (<service>)` ×7 | the image builds, has no HIGH/CRITICAL OS or library vulnerability with a fix; on the default branch it is pushed to GHCR under the commit SHA | ✅ |
| `platform smoke, security and performance` | the whole system works from a cold start with generated secrets; the attacks are all refused; 100 concurrent shoppers meet error-rate ≤ 1 %, browse p95 ≤ 500 ms, order p95 ≤ 2 s | ✅ |
| `release the images` | default branch only, after every gate above passed: the commit's images (already pushed under the SHA) are tagged `latest`. An image that a later gate fails is never promoted | n/a |
| `deploy to staging` | default branch only, after `release`, only when the repository variable `DEPLOY_ENABLED` is `true`, runs in the protected `staging` environment (required reviewers / wait timer are configured there); renders the production overlay with this commit's images, applies everything except Order Service and waits for those rollouts, then Order Service (Order calls Inventory endpoints a release may add, so Inventory goes first), **rolls back** if any fails | n/a |

**Branch protection** (set once in the repository settings): require the status checks above on the default branch, require a pull request
and one review, disallow force-push. The pipeline cannot enforce its own gates; the repository settings do.

## Nightly (`nightly.yml`)

> **Paused since 2026-10-19.** The nightly schedule and the weekly cache cleanup (`cache-cleanup.yml`) are commented out at the owner's
> request; both still run on demand from the Actions tab. To resume, uncomment the `schedule:` lines in the two workflows.
> Dependabot version updates are paused the same way (`open-pull-requests-limit: 0` on every entry of `.github/dependabot.yml`;
> remove it to resume). GitHub does not let the "Dependabot Updates" workflow be disabled from the Actions tab.

Two jobs too long for every pull request: the **failure matrix** (`scripts/chaos/run.py`, ~30 minutes: kills and freezes services,
partitions the network, restarts Kafka and PostgreSQL, and checks the invariants in the databases) and the **baseline-vs-optimized load test**
(`scripts/perf/run.py`). Their reports are attached to the run. When a scenario of the failure matrix fails, the log of every container
at that moment is attached too (`chaos-diagnostics`): the matrix says what failed, the logs say why.

Started by hand (Actions, "Run workflow"), the failure matrix takes two inputs: **scenarios** (for example `F03 F04`; empty runs all of
them) and **repeat** (how many times to run them, each time on a freshly started stack, the images built once). The run fails if any pass
fails, and the logs of every failed pass are kept (`chaos-diagnostics`, one folder for each pass). With scenarios given, the load test is
skipped. This is how a scenario that fails now and then is measured, and how a fix for it is shown to hold, without a local Docker stack.

## Supply chain and housekeeping

- Actions are pinned to major versions and kept current by Dependabot (`.github/dependabot.yml`), which also updates every Maven module and
  the Docker base image. Each update is a pull request that has to pass the full pipeline.
- Least privilege: the workflow's default permission is `contents: read`; only the jobs that publish images or upload scan results get
  `packages: write` / `security-events: write`.
- Images are built once and tagged with the commit SHA (immutable); `latest` (moving) is added only after every gate passed. The production
  overlay is rendered with the SHA, so what runs is exactly what was tested. The default branch is read from the repository
  (`github.event.repository.default_branch`), not written into the workflow, so renaming it cannot silently switch delivery off.
- Concurrency: a new push cancels the previous run of the same branch's pull request; deploys never overlap and are never cancelled half-way.
- Registry hiccups: each service's `.mvn/maven.config` makes Maven retry a download that gets 429, 500, 502, 503 or 504 (three retries,
  5 s growing backoff) instead of failing on the first answer; Maven's own default retries only 429 and 503. It applies to CI, the image
  builds (the Dockerfile copies `.mvn/`) and local builds alike.

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
