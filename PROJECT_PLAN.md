# Project: PayGuard — A Learning Platform for Payment Resilience Engineering

## 1. Problem Statement

Payment systems fail silently in production more often than they fail loudly.
A downstream timeout, a stale connection pool, an unpropagated hotfix — any of
these can turn into a missed payment before anyone notices. Most teams only
learn to detect and recover from these failures after a real incident burns
them.

PayGuard is a small, self-contained payment processing system with built-in
chaos injection. It intentionally breaks itself in realistic ways (network
delays, downstream timeouts, DB lock contention, duplicate delivery) so you
can practice building the detection, retry, idempotency, and observability
patterns that prevent real payment outages — entirely on a laptop, with no
cloud bill.

## 2. Learning Objectives

By the end of this project you should be able to:
- Containerize a multi-service application with Docker and Docker Compose
- Run a local Kubernetes cluster (kind) and deploy real workloads to it
- Write CI pipelines that lint, test, build, and publish container images
- Practice GitOps-style CD with Argo CD or Flux
- Instrument services with OpenTelemetry and visualize them in Grafana
- Reason about idempotency, retries, backoff, and failure injection in a
  distributed system
- Use Terraform to declaratively manage even local infrastructure

## 3. System Architecture

```
                 ┌─────────────┐
   client ──────▶│  Payment API │──────▶ Postgres (payments table)
                 └──────┬──────┘
                        │ publishes event
                        ▼
                 ┌─────────────┐        ┌────────────────┐
                 │   Queue      │──────▶│  Worker Service │
                 │ (Redis/RMQ)  │        │ (processes,     │
                 └─────────────┘        │  calls Mock     │
                                         │  Downstream)     │
                                         └────────┬────────┘
                                                  │
                                         ┌────────▼────────┐
                                         │ Mock Downstream  │
                                         │ Processor (with  │
                                         │ Chaos Injector)  │
                                         └─────────────────┘

   All services emit OpenTelemetry traces/metrics ──▶ Prometheus ──▶ Grafana
```

### Services
| Service | Responsibility | Language (suggested) |
|---|---|---|
| `payment-api` | Accepts payment requests, writes to DB, enqueues job | Java 21 / Spring Boot |
| `worker` | Pulls from queue, calls downstream, handles retry/idempotency | Java 21 / Spring Boot |
| `mock-downstream` | Simulates a processor; chaos-injectable | Java 21 / Spring Boot |
| `chaos-injector` | Config-driven fault injection (latency, drops, 500s) | Sidecar or lib |
| Postgres | Source of truth for payment state | — |
| Redis Streams | Queue between API and worker | — |
| Prometheus + Grafana | Metrics, dashboards, alerting | — |
| OpenTelemetry Collector | Central trace/metric pipeline | — |

## 4. Phased Roadmap

### Phase 1 — Local app, no containers yet
Build `payment-api` and `worker` running directly on your machine, talking to
a local Postgres and Redis. Goal: correct business logic and a manual test of
the happy path and one failure path.

### Phase 2 — Git discipline
Initialize the repo properly: trunk-based branching, conventional commits,
PR template, `.gitignore`, branch protection notes (even if solo, practice
the workflow).

### Phase 3 — Containerize
Dockerfile per service, multi-stage builds, `docker-compose.yml` wiring
everything together. Goal: `docker compose up` gives you the whole system.

### Phase 4 — CI
GitHub Actions workflow: lint → unit test → build image → (optionally) push
to local registry or GHCR. Add a badge to your README.

### Phase 5 — Local Kubernetes
Stand up `kind`, translate Compose into Kubernetes manifests (Deployments,
Services, ConfigMaps, Secrets), then convert to a Helm chart.

### Phase 6 — GitOps CD
Install Argo CD (or Flux) into your kind cluster. Point it at your repo.
Practice: change a manifest, push, watch it reconcile automatically.

### Phase 7 — Chaos + Observability
Wire in the chaos injector, OpenTelemetry tracing across all three services,
and Prometheus/Grafana dashboards. Build one alert rule (e.g. error rate
> 5% for 2 minutes) and one runbook doc describing what to do about it.

**Requirement (added 2026-07-07):** an observability strategy covering
system health/resource stats per service, transaction volume, and
autoscaling behavior, using Prometheus and/or Grafana. Autoscaling
specifically only becomes meaningful once Phase 5 puts services on
Kubernetes (HPA needs a Deployment to scale) — until then this phase's
dashboards cover request/transaction volume and per-service resource stats,
with an autoscaling panel added once Phase 5 exists. This was raised while
Phase 3 (containerize) was already complete; per this doc's phased,
one-at-a-time approach, it's captured here rather than built early.

**Requirement (added 2026-07-08):** the ability to performance/load test the
system and visibly observe it scale up and down in response to volume — a
`HorizontalPodAutoscaler` plus a load-generation tool, with the
scaling event itself visible on the Grafana dashboards above (not just
inferred from `kubectl get pods`). Phase 5 (Kubernetes) and Phase 6 (Argo
CD GitOps, `selfHeal: true`) are both complete by the time this is being
built, which introduces a real conflict this phase's design must resolve
explicitly: an HPA mutates a Deployment's live `replicas` count outside of
Git, and Argo CD's `selfHeal` is specifically designed to revert exactly
that kind of drift back to whatever `infra/k8s/` declares (proven live in
Phase 6's own demo). Wanted before Phase 8.

### Phase 8 (stretch) — Terraform for local infra
Use Terraform's `docker` or `kind` providers to declaratively stand up the
whole local environment instead of shell scripts.

## 5. Definition of Done (per phase)
Each phase should end with:
1. Working code, committed
2. A short `PHASE_N_NOTES.md` capturing what you learned and any gotchas
3. Updated root `README.md` reflecting current state of the system
4. A short `docs/demos/PHASE_N_DEMO.md`: exact steps to see the phase's new
   capability working (start command, URL/curl/command to run, what you
   should observe) — written for a product-manager audience, not a developer
   re-deriving the design

## 6. Repo Structure (target)

```
payguard/
├── PROJECT_PLAN.md
├── CLAUDE.md
├── README.md
├── docker-compose.yml
├── services/
│   ├── payment-api/
│   ├── worker/
│   └── mock-downstream/
├── infra/
│   ├── k8s/
│   ├── helm/
│   └── terraform/
├── .github/
│   └── workflows/
└── docs/
    ├── phase-notes/
    └── runbooks/
```
