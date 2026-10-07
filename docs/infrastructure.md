# Production infrastructure (Phase 11)

How Mercury is packaged and run as a platform: container images, a production-like Docker Compose stack, and
Kubernetes manifests. Everything below was built and checked; section 7 says exactly what was and was not
exercised.

## 1. Images

One standardized recipe, `infrastructure/docker/Dockerfile.service`, builds all four Spring Boot services
(the build context is the service's own folder):

| Stage | Does |
|---|---|
| `build` | `eclipse-temurin:21-jdk`: dependencies resolved first (cached), then `./mvnw package` |
| `layers` | splits the jar with Spring Boot's layer tools (`dependencies`, `spring-boot-loader`, `snapshot-dependencies`, `application`), so a code change only rewrites the small application layer |
| `runtime` | `eclipse-temurin:21-jre-alpine`: no JDK, no build tools |

Runtime hardening in the image: fixed non-root user (uid/gid `10001`), `java` as PID 1 in exec form (receives
SIGTERM, which triggers Spring's graceful shutdown), `-XX:MaxRAMPercentage=70` so the heap follows the
container's memory limit, `-XX:+ExitOnOutOfMemoryError` so an out-of-memory process dies and is restarted
rather than limping, and a `HEALTHCHECK` on the readiness endpoint. Image sizes: about 400–450 MB.

## 2. Health, readiness and graceful shutdown

Every service exposes (configured in its `application.properties`):

| Endpoint | Meaning | Depends on |
|---|---|---|
| `/actuator/health/liveness` | the process is alive; failing it restarts the container | nothing external |
| `/actuator/health/readiness` | the service should receive traffic | the database only |
| `/actuator/health` | overall health | all indicators |

Readiness deliberately does **not** include Kafka: with Kafka down the REST API and the outbox still work (events
wait), so taking the service out of rotation would turn a partial outage into a full one.

`server.shutdown=graceful` with a 25 s phase (`SHUTDOWN_TIMEOUT`): on SIGTERM the service stops accepting new
requests, lets in-flight ones finish, stops its Kafka consumers, then closes its pools. Compose waits 40 s
(`stop_grace_period`), Kubernetes 45 s (`terminationGracePeriodSeconds`) plus a 5 s `preStop` sleep so the pod
is removed from the Service endpoints before the process is told to stop. Verified: the log shows
`Commencing graceful shutdown` → `Graceful shutdown complete` → consumer stopped → JPA closed.

## 3. Docker Compose

```bash
cp .env.example .env                                        # set POSTGRES_PASSWORD
docker compose -f docker-compose.yml --profile platform up -d --build
scripts/smoke-test.sh                                       # end-to-end check
docker compose -f docker-compose.yml --profile platform down        # add -v to delete the data volumes
```

| File | Purpose |
|---|---|
| `docker-compose.yml` | the production-like stack: infrastructure + the `platform` profile (the four services) |
| `docker-compose.override.yml` | developer conveniences, applied automatically unless you pass `-f`: host ports for Postgres 5432, Kafka 9092, Qdrant 6333 and a default dev password |

- **Infrastructure** (always started): `postgres:17`, `apache/kafka:3.9.0` (KRaft), `qdrant/qdrant:v1.19.1`,
  each with a health check and a **named volume** (`postgres_data`, `kafka_data`, `qdrant_data`).
  `infrastructure/docker/postgres/init-databases.sh` creates one database per service on first start.
- **Network isolation:** the data tier is on the `data` network, which is `internal` (no route out, no published
  ports). Services join `data` and `edge`; only their HTTP ports are published, bound to `127.0.0.1`. In the
  production-like run the databases and Kafka are reachable only from other containers.
- **Startup order:** `depends_on: condition: service_healthy`. Postgres and Kafka first, then Product and
  Inventory, then Order (which needs both) and Notification.
- **Resource limits** (`deploy.resources.limits`): Postgres 384 MB, Kafka 640 MB (heap 384 MB), Qdrant 384 MB,
  services 448–512 MB each. Measured at idle after a smoke test: about 1.65 GB for all seven containers.
- **Container hardening:** read-only root filesystem with a 64 MB `/tmp` tmpfs, all capabilities dropped,
  `no-new-privileges`, bounded JSON logs.
- Select the order flow with `ORDER_RESERVATION_MODE=ASYNC` (see [async-reservation.md](async-reservation.md));
  verified in containers: `202` → `CONFIRMED`, replay `200`.

`.env` is git-ignored; `.env.example` is the template. `POSTGRES_PASSWORD` is intentionally not defaulted in
`docker-compose.yml` (an empty value makes Postgres refuse to initialise a new volume).

## 4. The checkpoint: `scripts/platform-check.sh`

Runs the whole claim in its own compose project (`mercury-verify`, no developer override), so it never touches a
local development database:

1. `up --build`, wait until **every** container is healthy
2. infrastructure checks: the five databases exist, Kafka lists its topics, Qdrant answers
3. smoke test: create product + stock, place an order, wait for `CONFIRMED`, replay it (same order, stock
   reserved once), see the notification
4. restart all four services, wait healthy
5. restart Postgres, Kafka and Qdrant too, wait healthy, services reconnect
6. the order is still `CONFIRMED` and the stock is unchanged
7. `down` (containers removed, volumes kept) then `up`: the order is still there
8. smoke test again

Result: **passed on the first run**; all seven containers healthy within about a minute.

## 5. Kubernetes (`infrastructure/kubernetes`)

Kustomize: `base/` plus overlays.

| Piece | In `base/` |
|---|---|
| Namespace `mercury` | Pod Security `baseline` enforced, `restricted` warned |
| ConfigMaps | shared non-secret configuration (service URLs, database URLs, Kafka address, order mode); Postgres init script |
| Secret | `mercury-secrets`, **generated** from `secrets.env` (git-ignored). `scripts/k8s-create-secrets.sh` writes a random password; a missing file fails the build on purpose |
| Postgres, Kafka, Qdrant | StatefulSets with PVCs (5 Gi / 5 Gi / 2 Gi) and headless Services; probes; resource limits |
| Four services | Deployment (2 replicas, rolling update with `maxUnavailable: 0`), Service, HorizontalPodAutoscaler (CPU 70 %, memory 80 %, scale-down stabilisation 5 min), PodDisruptionBudget |
| NetworkPolicies | default-deny ingress; the data tier accepts only the application pods; services accept each other, `ingress-nginx` and `monitoring` |

Each service pod: `startupProbe` + `livenessProbe` on `/actuator/health/liveness`, `readinessProbe` on
`/actuator/health/readiness`, `runAsNonRoot` uid 10001, read-only root filesystem + in-memory `/tmp`,
capabilities dropped, `RuntimeDefault` seccomp, no service-account token, topology spread across nodes.

Overlays: `overlays/local` (one replica, images from the local Docker daemon) and `overlays/production`
(more replicas, registry image names; CI sets the tag to the git SHA).

```bash
scripts/k8s-create-secrets.sh
kubectl apply -k infrastructure/kubernetes/overlays/local
```

## 6. Configuration summary

All settings are environment variables with defaults (see each service's `application.properties` and the
service docs). New in this phase: `SHUTDOWN_TIMEOUT` (default `25s`) and `MERCURY_VERSION` (image tag).

## 7. What was verified, and what was not

Verified by running it: image builds, the compose stack, health/readiness, the smoke test (SYNC and ASYNC),
service restart, data-tier restart, `down`/`up` with volumes, non-root + read-only filesystem, graceful
shutdown, memory use. The Kubernetes manifests were rendered with `kubectl kustomize` and validated against the
Kubernetes 1.31 schemas with `kubeconform --strict` (58 resources, 0 invalid) and their ConfigMap/Secret references
were cross-checked.

**Not verified:** applying the manifests to a running cluster (no cluster tooling in this environment, and the
Docker VM has about 4 GB of RAM, too little for a cluster plus the platform). HPA behaviour and NetworkPolicy
enforcement need a real cluster with metrics-server and a policy-aware CNI. This is the first thing to run once a
cluster is available (`kind` is enough).

## 7b. Phases 12-17 additions

- **Secrets.** `scripts/init-secrets.sh` generates `.env` (random database, admin and service-to-service secrets) and the JWT key pair under `.secrets/`
  (both git-ignored); nothing has a default. Compose mounts the keys as secrets: the private key only into the user-service. Kubernetes uses the same
  values through `scripts/k8s-create-secrets.sh` (`secrets.env`, `jwt-*.pem`, both git-ignored).
- **New services**: `user-service` (8085), `recommendation-service` (8086), `api-gateway` (8090), all built from the same Dockerfile, with the same probes,
  limits and hardening; plus Redis. In Kubernetes only the gateway is a `LoadBalancer`; a NetworkPolicy lets it, and only it, take traffic from outside the namespace.
- Observability overlay: `docker-compose.observability.yml` ([observability.md](observability.md)). Chaos overlay and harness: [resilience.md](resilience.md).
  Performance overlay (nginx load balancer, scaling): [performance.md](performance.md).

## 8. Known limitations

- Single-node Kafka and single-instance Postgres/Qdrant: appropriate for development and staging, not an HA
  data tier. Production would use managed services or operators.
- Compose resource limits assume at least 4 GB for the Docker VM.
- The local development override keeps a default password for convenience; Phase 15 removes default credentials.
- No ingress controller or TLS is defined yet (the API gateway is a later phase).
