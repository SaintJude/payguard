# Phase 1 Tasks — Local App, No Containers

Goal: `payment-api` and `worker` run directly on your machine, talking to a
local Postgres and Redis (installed via Homebrew, not Docker yet). The point
of this phase is correct business logic before any infra complexity.

## Setup
- [x] Choose a language for the services (Java 21 / Spring Boot — see CLAUDE.md)
- [x] `brew install postgresql redis` and get both running locally
- [x] Create `payments` database and one table: `payments`
      (`id, amount, status, idempotency_key, created_at, updated_at`)

## payment-api
- [x] `POST /payments` — accepts `{amount, idempotency_key}`, writes a row
      with status `pending`, pushes a job onto the queue, returns `202` with
      payment id
- [x] `GET /payments/:id` — returns current status
- [x] Duplicate `idempotency_key` should not create a second row

## worker
- [x] Consumes jobs from the queue
- [x] Calls a stubbed "downstream processor" function (just returns success
      for now — chaos comes later)
- [x] Updates payment status to `completed` or `failed`
- [x] Implement at least one retry with backoff for a simulated transient
      failure (hardcode a failure on the first attempt to prove retry works)

## Manual verification
- [x] Happy path: submit a payment, poll until `completed`
- [x] Idempotency: submit the same `idempotency_key` twice, confirm only one
      payment record exists
- [x] Retry: confirm a forced first-attempt failure still resolves to
      `completed` after retry

## Wrap-up
- [x] Write `docs/phase-notes/PHASE_1_NOTES.md`: what you built, any
      surprises, what you'd do differently
- [x] Commit with message `feat: phase 1 — local payment-api and worker`
