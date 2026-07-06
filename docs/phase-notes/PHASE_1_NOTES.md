# Phase 1 Notes — Local payment-api and worker

## What was built

- `payment-api`: `POST /payments` (idempotent via unique DB constraint),
  `GET /payments/:id`, Flyway-owned schema, publishes to Redis Stream
  `payments-stream` on create.
- `worker`: consumer-group-based Redis Streams consumer (`worker-group` /
  `worker-1`), in-process Spring Retry with backoff around a stubbed
  downstream call that fails once per payment, status updates to
  `COMPLETED`/`FAILED`, ack-on-terminal-outcome semantics.
- A small static test portal (`payment-api`'s `/`) for manually submitting
  and watching payments resolve, since the plan otherwise offered no way to
  interact with the system except curl.

## Surprises / gotchas

- **Environment**: Homebrew's plain `openjdk` formula tracks the latest
  release (26), and Lombok can't yet do annotation processing under it —
  every `mvn` build silently produced "cannot find symbol" errors for
  generated getters/setters until `JAVA_HOME` was pinned to `openjdk@21` in
  the Makefile. Worth remembering for Phase 3+ when Dockerfiles pick a base
  image.
- **The big one**: the worker process was exiting on its own ~200ms after
  every startup, with no error surfaced anywhere obvious. Root cause: its
  stream-consuming thread was marked as a JVM daemon thread, and since the
  worker has no web server keeping the process alive, the JVM had nothing
  non-daemon left to run once `main()` returned — so it exited by design,
  taking the one thread that mattered with it. This produced red herrings
  (Redis "connection closed prematurely" errors that were actually just the
  shutdown hook racing the poll thread) before the real cause was found by
  running the jar standalone in the foreground and watching it die
  unprompted.
- Minor: `BUSYGROUP` detection needed to check the exception's cause chain,
  not its top-level message — Spring Data Redis wraps Lettuce's specific
  error underneath a generic `RedisSystemException`.
- Minor: GNU Make's `$(wildcard ...)` inside a multi-line recipe doesn't
  reliably see files created by an earlier line in the same recipe
  (directory-listing caching) — switched the Makefile to hardcoded jar paths
  instead of globbing.

## What I'd do differently

- Run the worker standalone in the foreground earlier — `make verify`'s
  "start in background, poll, then tear down" flow hid the daemon-thread bug
  behind confusing Redis errors for longer than necessary. A quick manual
  foreground run would have shown the process dying instantly.
