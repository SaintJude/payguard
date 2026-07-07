# Phase 3 Tasks — Containerize

Goal: `payment-api` and `worker` each get a Dockerfile (multi-stage build:
compile with Maven, run on a slim JRE), and a root `docker-compose.yml` wires
them together with Postgres and Redis so `docker compose up` brings up the
whole system with no local Homebrew services required.

## Setup
- [x] `payment-api/Dockerfile` — multi-stage build (Maven build stage → slim
      JRE runtime stage), exposes the API port
- [x] `worker/Dockerfile` — multi-stage build, same pattern, no exposed port
      (no HTTP surface)
- [x] Root `docker-compose.yml` wiring `payment-api`, `worker`, `postgres`,
      `redis` together with the right env vars, ports, and startup ordering
- [x] Confirm Flyway migrations still run correctly against the containerized
      Postgres (schema ownership stays with `payment-api`, per Phase 1 design)
      — confirmed via `payment-api`'s healthcheck passing, which per the
      design doc can only happen after Flyway's migration completes

## Manual verification
- [x] `docker compose up --build` brings up all four containers cleanly
- [x] Happy path from `docs/demos/PHASE_1_DEMO.md` still works end-to-end
      against the containerized stack (submit a payment, poll until
      `COMPLETED`)
- [x] Stopping and restarting the stack (`docker compose down` /
      `docker compose up`) doesn't lose committed payment data (Postgres
      volume persists) — verified: a payment submitted before `down` was
      still readable via `GET /payments/:id` after `up` recreated all four
      containers

## Wrap-up
- [x] Write `docs/phase-notes/PHASE_3_NOTES.md`
- [x] Write `docs/demos/PHASE_3_DEMO.md`
- [x] Update root `README.md` and `CLAUDE.md`'s Current Phase line
- [x] Commit with message `feat: phase 3 — containerize payment-api and worker`
