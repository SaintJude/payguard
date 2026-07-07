# CLAUDE.md — Instructions for Claude Code in this repo

This file is read by the Claude plugin in VS Code (Claude Code) to understand
project context. Keep it up to date as the project evolves.

## What this project is

PayGuard: a local-only learning project simulating a payment processing
system with intentional chaos injection, used to practice containers, git,
CI/CD, and cloud-native tooling. See `PROJECT_PLAN.md` for the full plan.

## Current phase

> Update this line as you progress: **Phase 6 — GitOps CD (complete)**

## Working agreements for Claude Code

- Work one phase at a time, per `PROJECT_PLAN.md`. Do not jump ahead to
  containers/K8s/CI work until the current phase's Definition of Done is met.
- Prefer small, reviewable commits with conventional commit messages. This is
  a required convention, not just an observed habit — every commit message
  starts with one of:
  - `feat:` — new capability (a new endpoint, a new service behavior)
  - `fix:` — a bug fix to existing behavior
  - `docs:` — documentation only (phase notes, design docs, README, this file)
  - `test:` — tests only, no production code change
  - `chore:` — everything else that isn't user-facing (tooling, CI config,
    `.gitignore`, dependency bumps)
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

## Tech choices

- Language for services: Java 21 (LTS)
- Framework: Spring Boot
- Build tool: Maven
- Queue: Redis Streams
- Local Kubernetes: kind
- CD tool: Argo CD (default) or Flux
- Observability: Prometheus + Grafana + OpenTelemetry Collector

## Useful commands (fill in as they're established)

```bash
# Local dev
docker compose up --build

# Run tests
mvn test

# Run a service locally
mvn spring-boot:run

# Local k8s (after `docker compose build` — images must be loaded into kind)
kind create cluster --name payguard
kind load docker-image payguard-payment-api:latest --name payguard
kind load docker-image payguard-worker:latest --name payguard
kubectl apply -k infra/k8s/
# or, via Helm instead of raw manifests:
helm install payguard infra/helm/payguard -n payguard --create-namespace

# GitOps (after the above; Argo CD then owns infra/k8s/ going forward —
# don't kubectl apply/helm upgrade payguard's own resources by hand anymore)
kubectl apply -f infra/argocd/application.yaml
kubectl port-forward svc/argocd-server -n argocd 8080:443
```

## Multi-agent workflow

Work is divided across three custom subagents (`.claude/agents/architect.md`,
`implementer.md`, `tester.md`) rather than done end-to-end in one long session,
to keep each agent's context scoped and to avoid replaying prior chat history.

For each phase or task:
1. **architect** reads `PROJECT_PLAN.md`, this file, and the phase's
   `docs/phase-notes/PHASE_N_TASKS.md`, and writes
   `docs/architecture/PHASE_N_DESIGN.md` (concept primer, decision,
   alternatives/tradeoffs, contracts). Never touches `services/` or `infra/`.
2. The orchestrator presents that doc's Concept Primer + Tradeoffs to the user
   as the required teaching checkpoint before any code is written.
3. **implementer** builds code + unit tests strictly against that design doc.
   If the doc doesn't cover something needed, it stops and adds a note under
   the doc's Open Questions rather than improvising.
4. **tester** builds/runs the system (direct process today, Docker Compose
   from Phase 3, kind/Helm from Phase 5, `act` for CI from Phase 4) and writes
   `docs/test-reports/PHASE_N_REPORT.md`. Never edits source.
5. On FAIL, the orchestrator re-invokes **implementer** pointed only at the
   report's "Failures Requiring Implementer Action" section — not a chat
   replay. Loop until PASS.
6. On PASS: **tester** additionally writes `docs/demos/PHASE_N_DEMO.md` — a
   short, product-manager-facing script (exact start command, URL/curl/command
   to run, what to observe) proving the phase's new capability, distinct from
   the test report's job of proving correctness to a developer. The
   orchestrator then commits, updates this file's Current Phase line and
   `PROJECT_PLAN.md`'s checkboxes, and proposes `PHASE_N_NOTES.md` for
   approval.

Local pipeline entrypoint: `Makefile` (`make test`, `make verify`, etc. — see
its comments for what each phase adds).

## Files Claude Code should always check before big changes

- `PROJECT_PLAN.md` — overall roadmap and current phase definition of done
- `docs/phase-notes/` — what's already been learned/decided, to avoid
  re-litigating settled choices
- `docs/architecture/` — design docs per phase (architect agent output)
- `docs/test-reports/` — verification results per phase (tester agent output)
- `docs/demos/` — PM-facing "how to see it working" scripts per phase (tester
  agent output)
