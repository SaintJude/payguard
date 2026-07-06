# Phase 1 Demo — Local payment-api and worker

## What's new this phase

A payment can now be submitted, gets picked up automatically by a background
worker, survives a simulated one-time failure by retrying itself, and ends up
marked complete — all running locally on your machine, no containers yet.

## See it yourself

1. Start both services:
   ```
   make start
   ```
   (First run downloads dependencies and may take a minute; after that it's
   a few seconds.)

2. Open **http://localhost:8080/** in your browser. Fill in an amount, leave
   the idempotency key as generated, and click **Submit payment**.

3. Watch the table: the new row appears with status `PENDING`, then flips to
   `COMPLETED` within about 1-2 seconds (that pause is the worker's simulated
   first-attempt failure being retried automatically — it's proof the retry
   path actually ran, not just that everything happened to work first try).

   Try submitting with the *same* idempotency key twice (click "new key" to
   get a fresh one, or edit the field back to a previous value) — the second
   submission returns the same payment id instead of creating a duplicate.

## Stop

```
make stop
```
