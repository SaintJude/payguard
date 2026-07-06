# CLAUDE.md — Instructions for Claude Code in this repo

This file is read by the Claude plugin in VS Code (Claude Code) to understand
project context. Keep it up to date as the project evolves.

## What this project is

PayGuard: a local-only learning project simulating a payment processing
system with intentional chaos injection, used to practice containers, git,
CI/CD, and cloud-native tooling. See `PROJECT_PLAN.md` for the full plan.

## Current phase

> Update this line as you progress: **Phase 1 — Local app, no containers**

## Working agreements for Claude Code

- Work one phase at a time, per `PROJECT_PLAN.md`. Do not jump ahead to
  containers/K8s/CI work until the current phase's Definition of Done is met.
- Prefer small, reviewable commits with conventional commit messages
  (`feat:`, `fix:`, `chore:`, `docs:`, `test:`).
- Before writing infrastructure code (Dockerfile, k8s manifests, Terraform,
  GitHub Actions), explain the concept briefly in plain language first, as
  if teaching it, then show the code. The user is learning these tools
  intentionally, not just trying to get to a working system fast.
- When there are multiple valid approaches (e.g. Redis vs RabbitMQ, Go vs
  Node), briefly state the tradeoff and recommend one rather than defaulting
  silently.
- After finishing a chunk of work, propose the content for that phase's
  `docs/phase-notes/PHASE_N_NOTES.md` rather than writing it silently.
- Do not add cloud provider dependencies (AWS/GCP/Azure) — everything must
  run locally (Docker, kind, local Postgres/Redis).
- Keep services minimal. This is a learning project, not a production
  system — avoid over-engineering (e.g. no need for a service mesh).

## Tech choices (fill in once decided)

- Language for services: _TBD_
- Queue: _TBD (Redis Streams vs RabbitMQ)_
- Local Kubernetes: kind
- CD tool: Argo CD (default) or Flux
- Observability: Prometheus + Grafana + OpenTelemetry Collector

## Useful commands (fill in as they're established)

```bash
# Local dev
docker compose up --build

# Run tests
# TBD once language/framework chosen

# Local k8s
kind create cluster --name payguard
kubectl apply -k infra/k8s/
```

## Files Claude Code should always check before big changes

- `PROJECT_PLAN.md` — overall roadmap and current phase definition of done
- `docs/phase-notes/` — what's already been learned/decided, to avoid
  re-litigating settled choices
