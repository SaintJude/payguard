# Phase 1 Design — Local Payment API + Worker

## Scope

This doc covers the Phase 1 architecture for `payment-api` and `worker` as
defined in `docs/phase-notes/PHASE_1_TASKS.md`. `payment-api` is already
built and working; the sections below document its design as **settled** (not
open for re-litigation) so the worker design has firm ground to build on.
The bulk of this doc designs the **worker service**, which today is only a
`pom.xml` + `application.yml` + a bare `PaymentStatus` enum.

Out of scope: containers, Kubernetes, CI, observability, real chaos injection
(all later phases). The "chaos" in Phase 1 is a single hardcoded, in-process
simulated failure — not the real chaos injector from Phase 7.

## Concept Primer

### Redis Streams: consumer groups vs. plain pub/sub vs. plain XREAD

Redis gives you three different ways to move a message from a producer to a
reader, and they behave very differently once things go wrong — which is the
whole point of this project.

**Pub/Sub** (`PUBLISH`/`SUBSCRIBE`) is fire-and-forget. If no subscriber is
connected at the moment a message is published, that message is gone forever.
There's no log, no replay, no "catch up." Fine for things like cache
invalidation broadcasts; unacceptable for a payment that must not be lost.

**Plain stream reads** (`XREAD`, no group) are better: the stream itself is a
persisted, append-only log, so a reader can start from any offset and replay
history. But every reader that calls `XREAD` on the same stream sees *every*
message. There's no built-in concept of "this message is claimed, don't give
it to anyone else" — if you had two worker processes both doing plain `XREAD`,
both would process every payment. That's fine for fan-out (e.g. "every
service should see every event") but wrong for a task queue where exactly one
worker instance should handle each payment.

**Consumer groups** (`XGROUP` + `XREADGROUP` + `XACK`) add the missing piece:
a named group tracks a shared read cursor across one or more "consumers"
(worker instances). Redis hands each stream entry to exactly one consumer
within the group. Critically, when a consumer reads an entry, Redis doesn't
just forget about it — it adds the entry to that consumer's **Pending Entries
List (PEL)**: an internal "I gave this to worker-1, but nobody has confirmed
they're done with it" tracking list. The entry stays there until the consumer
calls `XACK`. If a worker crashes mid-processing, its unacked entries sit in
the PEL indefinitely, visible to an operator (`XPENDING`) and reclaimable by
another consumer (`XCLAIM`) — nothing is silently lost.

This is exactly the durability property a payment queue needs, and it's why
`application.yml` already commits to `payguard.stream.group=worker-group` and
`payguard.stream.consumer=worker-1` — consumer groups, not pub/sub or plain
reads, were chosen before this doc was written. It also sets up Phase 7
cleanly: once the chaos injector starts killing workers mid-request, the PEL
is the mechanism that makes recovery possible instead of catastrophic data
loss.

### Where retry lives: in-process (Spring Retry) vs. queue-driven redelivery

There are two independent ways to get "try again on transient failure" out of
this system, and it matters which one you pick because it determines *when*
you `XACK`:

1. **Queue-driven redelivery**: don't ack a failed message; let it stay
   pending; a reclaim process (or the same consumer, later) picks it back up
   from the stream and tries again. Retry timing is driven by the queue.
2. **In-process retry**: when the handler hits a transient failure, retry the
   downstream call immediately, in a loop, without ever letting the stream
   know anything went wrong. Only ack once a final outcome (success or
   giving up) is reached. Retry timing is driven by application code.

Phase 1 uses **in-process retry** via Spring Retry (see Decision below). That
means, from the stream's point of view, "handling a message" includes all of
its retries — the stream only finds out about one outcome per message: done
(ack) or blew up unexpectedly (leave pending).

## Decision

### payment-api (already built — documented as settled)

- **Queue**: Redis Streams, stream key `payments-stream`. `PaymentService`
  publishes `{paymentId: <uuid-string>}` via
  `redisTemplate.opsForStream().add(...)` inside the same method that saves
  the row, but *not* in the same atomic unit as the DB write — the class
  javadoc already flags this as the classic dual-write problem ("if the
  process crashes between save() and the stream add, the payment is stuck in
  PENDING forever... a production system would use an outbox pattern").
  Accepted as a known limitation for a learning project; not revisited here.
- **Idempotency**: enforced at the DB layer via a `UNIQUE` constraint on
  `idempotency_key`, checked with a `findByIdempotencyKey` lookup before
  insert. A duplicate `POST /payments` returns the original row (still `202`,
  same id) rather than erroring or creating a second row.
- **Schema ownership**: `payment-api` owns the `payments` table via Flyway
  (`V1__init_payments_table.sql`). `worker` must never run migrations
  (`ddl-auto: none`, no Flyway dependency) — this is already reflected in its
  `application.yml` and its `pom.xml` has no `flyway-core` dependency.

### worker (new — this phase's actual design work)

**1. Consumer group setup.** On startup, the worker attempts
`XGROUP CREATE payments-stream worker-group 0 MKSTREAM`. `MKSTREAM` creates
the stream if `payment-api` hasn't published anything yet; starting the group
at offset `0` (not `$`) means if the worker is ever restarted, it will still
see any entries that arrived before it existed rather than silently skipping
them. Redis returns a `BUSYGROUP` error if the group already exists — this is
expected on every restart after the first and is swallowed, not treated as a
failure.

**2. Consuming.** A single background thread (started from an
`ApplicationRunner`, not the main thread — a blocking read must not block
Spring's startup) loops calling:

```
XREADGROUP GROUP worker-group worker-1 COUNT 1 BLOCK 5000 STREAMS payments-stream >
```

`>` means "only entries never delivered to this group before." `BLOCK 5000`
means the call parks for up to 5s waiting for a new entry instead of busy-
looping. One entry at a time (`COUNT 1`) keeps processing strictly sequential,
which is the simplest possible model for Phase 1 (see Alternatives).

**3. Lookup + stubbed downstream call.** For each entry, the worker parses the
`paymentId` field into a `UUID`, loads the `Payment` row, and calls a stubbed
`DownstreamProcessor.process(paymentId)`.

**4. Hardcoded first-attempt failure.** `DownstreamProcessor` keeps an
in-memory `ConcurrentHashMap<UUID, AtomicInteger>` of attempt counts, keyed
by `paymentId`. On the *first* call for a given `paymentId` it throws
`TransientDownstreamException`; on any subsequent call for that same
`paymentId` it succeeds. This is deliberately **per-payment**, not "only the
very first payment the worker ever sees" — every payment gets exactly one
simulated failure on its first attempt, which is what lets
`scripts/verify.sh`'s happy-path scenario (which creates one payment and
expects it to reach `COMPLETED`) double as proof that retry works, and lets
the retry-specific scenario re-confirm it for a fresh payment created later.
This state is JVM-memory-only and resets on worker restart — acceptable for
Phase 1, called out under Open Questions/Out of Scope for later phases where
a worker restart mid-processing is itself the chaos being tested.

**5. Retry with backoff.** Handled declaratively with Spring Retry
(`spring-retry` + `spring-boot-starter-aop` are already Phase-0-scaffolded
dependencies — a strong signal this was the intended mechanism):

```java
@Retryable(retryFor = TransientDownstreamException.class,
           maxAttempts = 3,
           backoff = @Backoff(delay = 200, multiplier = 2))
public void processPayment(UUID paymentId) { ... }

@Recover
public void recover(TransientDownstreamException ex, UUID paymentId) { ... }
```

This lives on a separate bean (`PaymentProcessingService`) from the stream
consumer loop, because Spring Retry works via a dynamic proxy — calling an
`@Retryable` method on `this` from within the same class silently bypasses
the proxy and the retry never fires. Given the stub fails exactly once per
payment, in practice attempt 2 always succeeds (total added latency ~200ms);
`@Recover` exists as the exhaustion path but Phase 1's manual verification
never exercises it under normal operation.

**6. Status update + what FAILED means.** On successful `process()` (first
try or after retry), `processPayment` sets `status = COMPLETED`,
`updatedAt = now`, and saves. If retries are exhausted, `@Recover` sets
`status = FAILED`, `updatedAt = now`, and saves instead. **In Phase 1, FAILED
means "retries exhausted," full stop** — there is no separate "terminal /
non-retryable downstream rejection" concept yet, because the stubbed
processor only ever throws the one retryable exception type. That distinction
becomes real once Phase 7's chaos injector can return genuinely permanent
errors (e.g. a 400-equivalent) that shouldn't be retried at all; Phase 1's
`@Retryable(retryFor = TransientDownstreamException.class)` already leaves
room for that by scoping retry to a specific exception type rather than
"anything thrown."

If the `Payment` row referenced by an incoming message can't be found (should
not happen in Phase 1's single-writer flow, but defensive nonetheless), the
worker logs an error and treats the message as handled (acked) rather than
retrying forever against a row that will never appear.

**7. Acknowledgment (XACK) semantics.** The worker acks a message after
`processPayment` returns **normally** — which happens for *both* terminal
outcomes, `COMPLETED` and `FAILED` (via `@Recover`, which also returns
normally). It does **not** ack if an *unexpected* exception propagates out of
`processPayment` (e.g. the DB is unreachable when trying to persist the final
status) — that entry is left in the Pending Entries List rather than acked,
so it isn't silently lost; Phase 1 does not implement any automatic PEL
reclaim (`XCLAIM`) to pick it back up, so today that just means "a human
would notice it stuck in `XPENDING`." That reclaim loop is future work (ties
into Phase 7 chaos/observability).

The key point: **ack is a statement about "the stream doesn't need to deliver
this again," not "the payment succeeded."** Since retry already happened
in-process via Spring Retry before `processPayment` returns, redelivering a
`FAILED` message from the stream would just re-run the same doomed-or-lucky
sequence — it wouldn't add any value Spring Retry didn't already provide, and
leaving it pending forever would falsely suggest the message needs operator
attention. So: ack on any deliberate terminal outcome, don't ack on an
unhandled exception.

## Alternatives Considered & Tradeoffs

| Option | Pros | Cons | Why not chosen |
|---|---|---|---|
| Plain `XREAD` (no consumer group) | Simpler API, no group/PEL bookkeeping | No claim semantics — a second worker instance would double-process every payment; no crash recovery story | Already ruled out by `application.yml`'s `group`/`consumer` keys; also the whole teaching point of this phase |
| Redis Pub/Sub instead of Streams | Extremely simple, very low latency | No persistence — a payment published while the worker is down is lost forever; no replay | Payments must not be silently dropped; already ruled out by `payguard.stream.key` existing |
| Spring Data Redis `StreamMessageListenerContainer` (declarative listener) instead of a manual polling loop | Less boilerplate; framework manages the read loop and error handling | Hides the actual `XREADGROUP`/PEL mechanics behind an abstraction — for a project whose explicit goal is *learning* consumer groups, that's a cost, not a convenience; still requires manual group creation either way | A hand-rolled loop keeps the Redis Streams protocol visible, which is the concept this phase is teaching |
| Queue-driven redelivery as the retry mechanism (don't ack on failure, let the message get reclaimed and retried on a future poll) instead of in-process Spring Retry | Retry state lives in Redis, not JVM memory — survives a worker crash mid-retry | No backoff control per attempt without extra bookkeeping (last-attempt timestamp, attempt count stored somewhere durable); conflates "message delivery" concerns with "business retry policy" concerns; `spring-retry`/`spring-boot-starter-aop` are already scaffolded, implying in-process retry was the intended design | In-process retry with Spring Retry is simpler to implement correctly for Phase 1 and matches the dependencies already chosen; queue-driven redelivery is worth revisiting once Phase 7 introduces real worker crashes as a chaos scenario |
| Manual `try`/`catch` + `Thread.sleep` retry loop instead of `@Retryable` | No AOP-proxy self-invocation gotcha to explain; fully explicit | Reimplements what Spring Retry already does (attempt counting, exponential backoff, a typed recovery hook); more code to maintain for no behavioral gain | `spring-retry` + `spring-boot-starter-aop` are already project dependencies — using them is both less code and consistent with the plan already in the scaffold |
| Shared `common` Maven module for the `Payment` entity/`PaymentStatus` enum, used by both services | No duplication of the entity/enum between `payment-api` and `worker` | Couples the two services' build/release cycles; there's no parent aggregator POM in this repo, so introducing one is itself a structural decision beyond this phase's scope | Repo structure (two independent, sibling Maven modules with no shared parent) already implies per-service duplication; `worker` already has its own `PaymentStatus` enum copy. Revisit only if duplication becomes painful |

## Contracts

**Redis Stream**
- Key: `payments-stream` (matches `payguard.stream.key` in both services' `application.yml`)
- Message fields: single field `paymentId` → `String` (UUID, e.g. `"3fa8...`")
- Consumer group: `worker-group` (`payguard.stream.group`)
- Consumer name: `worker-1` (`payguard.stream.consumer`)
- Group creation: `XGROUP CREATE payments-stream worker-group 0 MKSTREAM` at
  worker startup; a `BUSYGROUP` error on this call is caught and ignored.
- Read call: `XREADGROUP GROUP worker-group worker-1 COUNT 1 BLOCK 5000 STREAMS payments-stream >`
- Ack call: `XACK payments-stream worker-group <record-id>`, issued only when
  `PaymentProcessingService.processPayment` (or its `@Recover` handler)
  returns without throwing.

**Worker JPA entity** (`com.payguard.worker.domain.Payment`, mirrors
`payment-api`'s entity field-for-field against the existing `payments` table
— no schema changes):
| Field | Column | Type |
|---|---|---|
| id | id | UUID, PK |
| amount | amount | BigDecimal |
| status | status | `PaymentStatus` enum, `EnumType.STRING` |
| idempotencyKey | idempotency_key | String |
| createdAt | created_at | Instant |
| updatedAt | updated_at | Instant |

`worker`'s `spring.jpa.hibernate.ddl-auto` stays `none`; it never runs Flyway.

**PaymentStatus** (`com.payguard.worker.domain.PaymentStatus`, already
present, unchanged): `PENDING`, `COMPLETED`, `FAILED`. No `PROCESSING` or
similar intermediate state is added in Phase 1 — see Out of Scope.

**New classes to implement**
- `com.payguard.worker.WorkerApplication` — `@SpringBootApplication`,
  `@EnableRetry`.
- `com.payguard.worker.repository.PaymentRepository extends JpaRepository<Payment, UUID>`.
- `com.payguard.worker.downstream.TransientDownstreamException extends RuntimeException`.
- `com.payguard.worker.downstream.DownstreamProcessor` — `@Component`,
  `void process(UUID paymentId)`; per-`paymentId` attempt counter as described
  in Decision §4.
- `com.payguard.worker.service.PaymentProcessingService` — `@Service`;
  `@Retryable(retryFor = TransientDownstreamException.class, maxAttempts = 3, backoff = @Backoff(delay = 200, multiplier = 2)) void processPayment(UUID paymentId)`;
  `@Recover void recover(TransientDownstreamException ex, UUID paymentId)`.
  Both methods: look up the `Payment`, log-and-return if absent, else set
  terminal status + `updatedAt` and save.
- `com.payguard.worker.stream.PaymentStreamConsumer` — `ApplicationRunner`
  (or starts a `Thread`/`ExecutorService` from one) that performs group
  creation once, then loops `XREADGROUP` → parse `paymentId` → call
  `PaymentProcessingService.processPayment` → ack on normal return, log (no
  ack) on unexpected exception.

**Config keys** (already present in `application.yml`, consumed as above):
`payguard.stream.key`, `payguard.stream.group`, `payguard.stream.consumer`.
No new config keys are introduced by this design.

**No new HTTP surface** — the worker has no REST endpoints in Phase 1.

## Open Questions

- [ ] None blocking. One deliberate deferral, not a blocker: automatic PEL
      reclaim (`XCLAIM`) for messages left unacked after an unexpected
      exception is not implemented in Phase 1 (see Decision §7) — flagged for
      revisit once Phase 7's chaos injector makes worker crashes a realistic
      scenario worth handling.

## Out of Scope

- Adding a `PROCESSING`/`IN_PROGRESS` intermediate `PaymentStatus` — would
  require changing an enum and possibly a migration that `payment-api` owns;
  not needed for Phase 1's binary "did it finish, and how" requirement.
- Automatic reclaim of pending-but-unacked stream entries (`XCLAIM`/`XPENDING`
  sweep) after a worker crash.
- Real chaos injection (network delay, forced 5xx, DB lock contention) — the
  Phase 1 "failure" is a single hardcoded, in-memory, per-payment exception
  used solely to prove the retry path works. The real chaos injector arrives
  in Phase 7.
- Horizontal scaling of the worker (multiple consumer instances in
  `worker-group`) — Phase 1 runs exactly one (`worker-1`); consumer groups
  make this possible later without a redesign, but it isn't exercised now.
- Outbox pattern / fixing the payment-api dual-write gap — documented as a
  known, accepted limitation, not addressed in this phase.
- Containerization, Kubernetes, CI, observability — later phases per
  `PROJECT_PLAN.md`.
