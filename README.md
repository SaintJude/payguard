# PayGuard

[![CI](https://github.com/SaintJude/payguard/actions/workflows/ci.yml/badge.svg)](https://github.com/SaintJude/payguard/actions/workflows/ci.yml)

A small, self-contained payment processing system with built-in chaos
injection — a learning project for practicing containers, git, CI/CD,
observability, and payment-resilience patterns (idempotency, retries,
backoff) entirely on a laptop. See [`PROJECT_PLAN.md`](PROJECT_PLAN.md) for
the full roadmap and learning objectives.

## Status

**Phase 7 — Chaos, Observability, Autoscaling: complete.**

`payment-api` and `worker` talk to two new services — `chaos-injector` and
`mock-downstream` — through which real, configurable failure can be
injected live. Every service is traced (Tempo) and monitored (Prometheus +
Grafana), with one real alert wired to Alertmanager. `payment-api` and
`worker` both scale automatically under load via `HorizontalPodAutoscaler`s,
and a `k6` script drives real traffic to trigger it. See
[`docs/demos/PHASE_7_DEMO.md`](docs/demos/PHASE_7_DEMO.md) for a
walkthrough — including what to expect when the load test pushes the
cluster to its resource limits (expected, self-healing, documented rather
than hidden).

## Running it

**Docker Compose** (simplest, Phase 3):
```
docker compose up --build   # build images + launch the whole stack
docker compose down         # shut it down (payment data persists in a named volume)
```

**Local Kubernetes** (Phase 5 — see
[`docs/demos/PHASE_5_DEMO.md`](docs/demos/PHASE_5_DEMO.md) for full steps):
```
kind create cluster --name payguard
docker compose build payment-api worker
kind load docker-image payguard-payment-api:latest --name payguard
kind load docker-image payguard-worker:latest --name payguard
kubectl apply -k infra/k8s/                                       # raw manifests
# or: helm install payguard infra/helm/payguard -n payguard --create-namespace
kubectl port-forward -n payguard svc/payment-api 8080:8080
```

**GitOps with Argo CD** (Phase 6 — see
[`docs/demos/PHASE_6_DEMO.md`](docs/demos/PHASE_6_DEMO.md) for full steps,
including a colima resource-sizing note — Argo CD needs more than the
default 2 CPU / 2GB colima profile):
```
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
kubectl apply -f infra/argocd/application.yaml
kubectl port-forward svc/argocd-server -n argocd 8080:443   # UI at https://localhost:8080
```
Once applied, don't `kubectl apply -k`/`helm upgrade` `payguard`'s own
resources by hand anymore — edit `infra/k8s/`, commit, push, merge; Argo CD
does the rest.

**Chaos, observability, and autoscaling** (Phase 7 — see
[`docs/demos/PHASE_7_DEMO.md`](docs/demos/PHASE_7_DEMO.md) for full steps):
```
# Observability platform (imperative, not Argo CD-tracked, install once)
helm install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  -n monitoring -f infra/observability/kube-prometheus-stack-values.yaml
helm install tempo grafana/tempo -n monitoring -f infra/observability/tempo-values.yaml
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
kubectl patch deployment metrics-server -n kube-system --type='json' \
  --patch-file=infra/observability/metrics-server-kubelet-insecure-tls-patch.yaml

# Trigger real autoscaling
kubectl port-forward -n payguard svc/payment-api 8080:8080
k6 run load/payment-load.js
kubectl get hpa -n payguard -w
```

Either way, once running, open **http://localhost:8080/** for a small test
portal to submit payments and watch them resolve (note: this reuses port
8080 — don't run the Argo CD UI port-forward and the `payment-api`
port-forward at the same time without picking different local ports).

Prefer running the services directly on the JVM instead (no containers at
all)? `make start` / `make stop` / `make verify` still work — see
[`docs/demos/PHASE_1_DEMO.md`](docs/demos/PHASE_1_DEMO.md) and the
[`run` skill](.claude/skills/run/SKILL.md) — just make sure only one of
Compose/Kubernetes/direct-JVM is holding ports 8080/5432/6379 at a time.

## Repo layout

- `services/payment-api/`, `services/worker/` — the original two services
- `services/mock-downstream/`, `services/chaos-injector/` — Phase 7's
  simulated processor and its fault-injecting proxy
- `infra/k8s/` — raw Kubernetes manifests (Phase 5); the GitOps source of
  truth Argo CD tracks as of Phase 6, now including PodMonitors, the
  Grafana dashboard, HPAs, and the alert rule (Phase 7)
- `infra/helm/payguard/` — Helm chart packaging the same system (Phase 5) —
  untouched, documented alternative, not the one Argo CD deploys
- `infra/argocd/` — the Argo CD `Application` resource pointing at
  `infra/k8s/` (Phase 6)
- `infra/observability/` — imperative Helm values for `kube-prometheus-stack`
  and Tempo, and the `metrics-server` kind patch — not Argo CD-tracked
  (Phase 7)
- `load/` — the `k6` load-generation script (Phase 7)
- `docs/architecture/` — per-phase design docs (concept, decision, tradeoffs,
  contracts), plus [`SYSTEM_ARCHITECTURE.md`](docs/architecture/SYSTEM_ARCHITECTURE.md),
  a current-state diagram of the whole system
- `docs/phase-notes/` — per-phase task checklists and retrospective notes
- `docs/test-reports/` — per-phase verification results
- `docs/demos/` — per-phase "how to see it working" scripts
- `docs/runbooks/` — operational runbooks for alerts (Phase 7)
- `.claude/agents/` — the architect/implementer/tester subagents this project's
  work is divided across (see `CLAUDE.md` for the workflow)

## Tech choices

Java 21, Spring Boot, Maven, Redis Streams, kind, Argo CD, OpenTelemetry,
Prometheus, Grafana, Tempo, k6. See [`CLAUDE.md`](CLAUDE.md) for the full
list and the working agreements this project follows.
