---
name: run
description: Launch and drive PayGuard (payment-api + worker) locally against Homebrew Postgres/Redis, and verify the payment flow end-to-end.
---

# Running PayGuard

Two Spring Boot services (`payment-api`, `worker`), direct JVM processes
against local Homebrew Postgres/Redis — no containers until Phase 3.

## Prerequisites

- `brew services list` should show `postgresql@16` and `redis` as `started`.
  If not: `brew services start postgresql@16 && brew services start redis`.
- **JDK gotcha**: plain `mvn`/`java` on this machine may resolve to
  Homebrew's unversioned `openjdk` (tracks latest, currently 26). Lombok
  cannot yet do annotation processing under JDK 26 — builds fail with
  confusing "cannot find symbol" errors for generated getters/setters that
  look like a code problem but aren't. The `Makefile` already pins
  `JAVA_HOME := /opt/homebrew/opt/openjdk@21` for all its targets, so prefer
  `make` targets over raw `mvn`/`java` invocations. If you must run Maven
  directly, prefix it: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn ...`.

## Run

```bash
cd payguard
make start
```

This builds both services, starts them as background processes, and returns
(it does not block). PIDs land in `.run/*.pid`, logs in `.run/*.log`
(gitignored). `payment-api` listens on port 8080; `worker` has no HTTP port.

Readiness check:

```bash
for i in {1..30}; do curl -sf http://localhost:8080/payments/00000000-0000-0000-0000-000000000000 -o /dev/null -w '%{http_code}' | grep -q 404 && break; sleep 1; done
```
(A 404 for a nonexistent id means the server is up and routing correctly.)

## Drive it

**Browser** (this runs on your actual machine, not a remote sandbox — just
open it): **http://localhost:8080/** is a small static test portal. Submit a
payment, watch the live table poll `GET /payments/{id}` until it flips from
`PENDING` to `COMPLETED` (~1-2s — that pause is the worker's built-in
one-time simulated failure being retried).

**curl**, if you want the raw flow instead:

```bash
curl -s -X POST http://localhost:8080/payments \
  -H 'Content-Type: application/json' \
  -d '{"amount":10.00,"idempotencyKey":"demo-1"}'
# → {"id":"<uuid>","status":"PENDING",...}

curl -s http://localhost:8080/payments/<uuid>
# poll until "status":"COMPLETED"
```

Submitting the same `idempotencyKey` twice returns the same payment id
instead of creating a duplicate row.

**Full scripted verification** (what CI/the tester agent runs — builds,
starts, runs `scripts/verify.sh`'s three scenarios, stops):

```bash
make verify
```

## Stop

```bash
make stop
```

## Gotchas

- The worker's consumer group persists in Redis across restarts —
  `redis-cli FLUSHALL` if you want a truly clean slate (e.g. to re-test the
  "first startup ever" path), not usually necessary otherwise.
- If `worker` seems to never process anything, check `.run/worker.log` for
  it actually staying up — earlier in this project a daemon-thread bug made
  the whole process exit ~200ms after startup with no obvious error at the
  point of failure. `ps aux | grep worker-0.1` (or check `.run/worker.pid`
  is still a live PID) is the fastest sanity check.
