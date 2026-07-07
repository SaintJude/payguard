# PayGuard

[![CI](https://github.com/SaintJude/payguard/actions/workflows/ci.yml/badge.svg)](https://github.com/SaintJude/payguard/actions/workflows/ci.yml)

A small, self-contained payment processing system with built-in chaos
injection — a learning project for practicing containers, git, CI/CD,
observability, and payment-resilience patterns (idempotency, retries,
backoff) entirely on a laptop. See [`PROJECT_PLAN.md`](PROJECT_PLAN.md) for
the full roadmap and learning objectives.

## Status

**Phase 6 — GitOps CD: complete.**

Argo CD now watches this repo's `main` branch and keeps the `payguard` kind
cluster in sync automatically — push a manifest change, Argo CD applies it;
edit the live cluster by hand, Argo CD reverts it. See
[`docs/demos/PHASE_6_DEMO.md`](docs/demos/PHASE_6_DEMO.md) for a
walkthrough. Docker Compose (Phase 3) and plain `kubectl`/`helm` (Phase 5)
still work for local iteration — Argo CD only manages the cluster once you
opt in by applying its `Application` resource.

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

- `services/payment-api/`, `services/worker/` — the two services built so far
- `infra/k8s/` — raw Kubernetes manifests (Phase 5); the GitOps source of
  truth Argo CD tracks as of Phase 6
- `infra/helm/payguard/` — Helm chart packaging the same system (Phase 5) —
  untouched, documented alternative, not the one Argo CD deploys
- `infra/argocd/` — the Argo CD `Application` resource pointing at
  `infra/k8s/` (Phase 6)
- `docs/architecture/` — per-phase design docs (concept, decision, tradeoffs, contracts)
- `docs/phase-notes/` — per-phase task checklists and retrospective notes
- `docs/test-reports/` — per-phase verification results
- `docs/demos/` — per-phase "how to see it working" scripts
- `.claude/agents/` — the architect/implementer/tester subagents this project's
  work is divided across (see `CLAUDE.md` for the workflow)

## Tech choices

Java 21, Spring Boot, Maven, Redis Streams, kind, Argo CD. See
[`CLAUDE.md`](CLAUDE.md) for the full list and the working agreements this
project follows.
