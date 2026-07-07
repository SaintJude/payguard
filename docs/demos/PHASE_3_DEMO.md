# Phase 3 Demo — Containerized payment-api and worker

## What's new this phase

The whole system — `payment-api`, `worker`, Postgres, and Redis — now runs as
four Docker containers, started with a single command. No more installing
Postgres/Redis on your laptop or running Java processes directly; the exact
same payment flow from Phase 1 (submit → simulated retry → complete) now
works fully containerized.

## See it yourself

1. Make sure nothing else is using ports 8080, 5432, or 6379 (e.g. stop
   Phase 1's `make start` processes and any local Postgres/Redis services).

2. Start the whole stack:
   ```
   docker compose up --build
   ```
   First run builds both images from source (a minute or two); after that,
   startup is a few seconds. You'll see each container come up in order —
   `postgres` and `redis` first, then `payment-api` once both are healthy,
   then `worker` once `payment-api` is healthy (that ordering guarantees the
   database table exists before `worker` ever tries to use it).

3. Open **http://localhost:8080/** in your browser — same test portal as
   Phase 1. Submit a payment and watch it flip from `PENDING` to `COMPLETED`.

4. To see the data survive a restart: stop everything with
   `docker compose down`, then `docker compose up` again (no `--build`
   needed) — the payment you submitted in step 3 is still there when you look
   it up, because Postgres's data lives in a Docker volume that isn't wiped
   by `down`.

## Stop

```
docker compose down
```
