# Phase 1 Tasks — Local App, No Containers

Goal: `payment-api` and `worker` run directly on your machine, talking to a
local Postgres and Redis (installed via Homebrew, not Docker yet). The point
of this phase is correct business logic before any infra complexity.

## Setup
- [ ] Choose a language for the services (Go or Node — pick one and stick
      with it for consistency across services)
- [ ] `brew install postgresql redis` and get both running locally
- [ ] Create `payments` database and one table: `payments`
      (`id, amount, status, idempotency_key, created_at, updated_at`)

## payment-api
- [ ] `POST /payments` — accepts `{amount, idempotency_key}`, writes a row
      with status `pending`, pushes a job onto the queue, returns `202` with
      payment id
- [ ] `GET /payments/:id` — returns current status
- [ ] Duplicate `idempotency_key` should not create a second row

## worker
- [ ] Consumes jobs from the queue
- [ ] Calls a stubbed "downstream processor" function (just returns success
      for now — chaos comes later)
- [ ] Updates payment status to `completed` or `failed`
- [ ] Implement at least one retry with backoff for a simulated transient
      failure (hardcode a failure on the first attempt to prove retry works)

## Manual verification
- [ ] Happy path: submit a payment, poll until `completed`
- [ ] Idempotency: submit the same `idempotency_key` twice, confirm only one
      payment record exists
- [ ] Retry: confirm a forced first-attempt failure still resolves to
      `completed` after retry

## Wrap-up
- [ ] Write `docs/phase-notes/PHASE_1_NOTES.md`: what you built, any
      surprises, what you'd do differently
- [ ] Commit with message `feat: phase 1 — local payment-api and worker`
