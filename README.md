# PayGuard

A small, self-contained payment processing system with built-in chaos
injection — a learning project for practicing containers, git, CI/CD,
observability, and payment-resilience patterns (idempotency, retries,
backoff) entirely on a laptop. See [`PROJECT_PLAN.md`](PROJECT_PLAN.md) for
the full roadmap and learning objectives.

## Status

**Phase 3 — Containerize: complete.**

`payment-api`, `worker`, Postgres, and Redis all run as Docker containers,
wired together with `docker-compose.yml`. A payment can be submitted, gets
picked up by the worker, survives a simulated transient failure via retry,
and resolves to `COMPLETED` — the same flow as Phase 1, now fully
containerized with no local Postgres/Redis/JVM install required.

## Running it

```
docker compose up --build   # build images + launch the whole stack
docker compose down         # shut it down (payment data persists in a named volume)
```

Then open **http://localhost:8080/** for a small test portal to submit
payments and watch them resolve. See
[`docs/demos/PHASE_3_DEMO.md`](docs/demos/PHASE_3_DEMO.md) for a walkthrough.

Prefer running the services directly on the JVM instead (no Docker)?
`make start` / `make stop` / `make verify` still work — see
[`docs/demos/PHASE_1_DEMO.md`](docs/demos/PHASE_1_DEMO.md) and the
[`run` skill](.claude/skills/run/SKILL.md) — just make sure Docker's
containers and the direct-JVM processes aren't both holding ports
8080/5432/6379 at the same time.

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
