# Phase 1 Test Report — worker service

## What was run

`make verify` (builds both services with `JAVA_HOME=/opt/homebrew/opt/openjdk@21`,
starts them as background JVM processes against local Postgres/Redis, runs
`scripts/verify.sh`'s three scenarios, stops both services).

Run multiple times in a row, plus the worker jar launched standalone in the
foreground, to distinguish a one-off flake from a deterministic bug.

## Result: PASS (after daemon-thread fix)

Scenario 1 (happy path) originally failed on every run — the submitted
payment never left `PENDING`. Scenarios 2/3 never got a chance to prove
anything meaningful since the worker wasn't actually processing messages.
After the fix below, `make verify` was run twice in a row (fresh Redis state,
then again against the group/stream left over from the first run, to confirm
the restart path specifically) and **all three scenarios passed both times**:

```
== Scenario 1: happy path (submit, poll until completed) ==
poll 1: PENDING
poll 2: COMPLETED
PASS: happy path
== Scenario 2: idempotency (same key twice -> one record) ==
PASS: idempotency
== Scenario 3: retry-then-complete ==
PASS: retry-then-complete
ALL SCENARIOS PASSED
```

## Root cause (superseded an earlier wrong diagnosis — see below)

Running `java -jar services/worker/target/worker-0.1.0-SNAPSHOT.jar` directly
in the foreground (no Makefile, no `make stop`, nothing else touching it)
shows the process **exits on its own within ~200ms of "Started
WorkerApplication"**, every time, with no external signal involved:

```
... Started WorkerApplication in 1.097 seconds ...
... Consumer group worker-group already exists on stream payments-stream; continuing
... [ionShutdownHook] Closing JPA EntityManagerFactory ...
... [ionShutdownHook] HikariPool-1 - Shutdown completed.
```

**Why**: `PaymentStreamConsumer.run()` starts the poll loop on a thread marked
`consumerThread.setDaemon(true)`. The worker has no `spring-boot-starter-web`
dependency (it's a non-web Spring Boot app), so nothing else keeps the process
alive. `ApplicationRunner.run()` returns immediately after spawning the
thread; once `SpringApplication.run()` returns from `main()`, the JVM has
**zero non-daemon threads left** (Spring's and Netty's internal threads are
daemon too) — so the JVM exits naturally per normal Java semantics, killing
the daemon poll thread mid-flight. The one thread whose job is to be this
service's entire reason for existing never gets a real chance to run.

This also retroactively explains every "Connection closed prematurely" /
"Unable to connect to Redis" error seen in earlier test runs of this report:
those were **not** transient network flakes. They were the Redis connection
being torn down by the JVM shutdown hook racing with the poll thread's first
read attempt, milliseconds after startup, on every single run. The two fixes
already applied for BUSYGROUP detection and poll-loop backoff are correct and
should stay, but neither was the actual blocker — this is.

## Fixes applied (fixed, verified)

- **Daemon thread (blocking, root cause)**: `consumerThread.setDaemon(true)`
  removed from `PaymentStreamConsumer.run()`. The thread is now non-daemon
  (`Thread`'s default), so the JVM stays alive for as long as `pollLoop()`
  runs, which is this service's intended lifetime.
- BUSYGROUP detection in `createConsumerGroup()` walks the cause chain
  instead of checking the top-level exception message.
- `pollLoop()`'s error catch sleeps 1 second before retrying instead of
  spinning with no delay.

All three fixes verified together via two consecutive `make verify` runs
(see Result above) — Phase 1's Definition of Done is met.
