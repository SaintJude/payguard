# PayGuard

A small, self-contained payment processing system with built-in chaos
injection — a learning project for practicing containers, git, CI/CD,
observability, and payment-resilience patterns (idempotency, retries,
backoff) entirely on a laptop. See [`PROJECT_PLAN.md`](PROJECT_PLAN.md) for
the full roadmap and learning objectives.

## Status

**Phase 1 — Local app, no containers: complete.**

`payment-api` and `worker` run as direct JVM processes against local
Homebrew Postgres and Redis. A payment can be submitted, gets picked up by
the worker, survives a simulated transient failure via retry, and resolves
to `COMPLETED`.

## Running it

```
make start   # build + launch both services in the background
make stop    # shut them down
make verify  # build, launch, run the automated verification scenarios, shut down
```

Then open **http://localhost:8080/** for a small test portal to submit
payments and watch them resolve. See
[`docs/demos/PHASE_1_DEMO.md`](docs/demos/PHASE_1_DEMO.md) for a walkthrough,
or the [`run` skill](.claude/skills/run/SKILL.md) for the full mechanics
(including a JDK version gotcha worth knowing about before you run `mvn`
directly).

## Repo layout

- `services/payment-api/`, `services/worker/` — the two services built so far
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
