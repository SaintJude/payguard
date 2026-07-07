# PayGuard

[![CI](https://github.com/SaintJude/payguard/actions/workflows/ci.yml/badge.svg)](https://github.com/SaintJude/payguard/actions/workflows/ci.yml)

A small, self-contained payment processing system with built-in chaos
injection — a learning project for practicing containers, git, CI/CD,
observability, and payment-resilience patterns (idempotency, retries,
backoff) entirely on a laptop. See [`PROJECT_PLAN.md`](PROJECT_PLAN.md) for
the full roadmap and learning objectives.

## Status

**Phase 5 — Local Kubernetes: complete.**

`payment-api`, `worker`, Postgres, and Redis now run on a local `kind`
Kubernetes cluster, as either plain manifests (`infra/k8s/`) or a Helm
chart (`infra/helm/payguard/`) — in addition to the Docker Compose setup
from Phase 3, which still works. See
[`docs/demos/PHASE_5_DEMO.md`](docs/demos/PHASE_5_DEMO.md) for a
walkthrough, including the self-healing behavior Compose doesn't give you.

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

Either way, once running, open **http://localhost:8080/** for a small test
portal to submit payments and watch them resolve.

Prefer running the services directly on the JVM instead (no containers at
all)? `make start` / `make stop` / `make verify` still work — see
[`docs/demos/PHASE_1_DEMO.md`](docs/demos/PHASE_1_DEMO.md) and the
[`run` skill](.claude/skills/run/SKILL.md) — just make sure only one of
Compose/Kubernetes/direct-JVM is holding ports 8080/5432/6379 at a time.

## Repo layout

- `services/payment-api/`, `services/worker/` — the two services built so far
- `infra/k8s/` — raw Kubernetes manifests (Phase 5)
- `infra/helm/payguard/` — Helm chart packaging the same system (Phase 5)
- `docs/architecture/` — per-phase design docs (concept, decision, tradeoffs, contracts)
- `docs/phase-notes/` — per-phase task checklists and retrospective notes
- `docs/test-reports/` — per-phase verification results
- `docs/demos/` — per-phase "how to see it working" scripts
- `.claude/agents/` — the architect/implementer/tester subagents this project's
  work is divided across (see `CLAUDE.md` for the workflow)

## Tech choices

Java 21, Spring Boot, Maven, Redis Streams, kind (later phases), Argo CD or
Flux (later phases). See [`CLAUDE.md`](CLAUDE.md) for the full list and the
working agreements this project follows.
