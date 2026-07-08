# Phase 7 Notes — Chaos, Observability, and Autoscaling

## What was built

By far the largest phase to date — six implementation waves:

1. **`mock-downstream`** (new) — a boring, stateless, always-succeeds
   simulated payment processor.
2. **`chaos-injector`** (new) — a standalone proxy service `worker` calls
   instead of `mock-downstream` directly, with five config-driven fault
   modes (`NONE`/`LATENCY`/`ERROR_5XX`/`ERROR_4XX`/`DROP`) toggled live via
   `GET`/`PUT /chaos/config`. This was a mid-design revision: the architect's
   first draft recommended an in-process lib inside `mock-downstream`; the
   user chose the standalone-service option instead, for more realistic
   network-level chaos simulation and a cleaner separation of concerns
   (`mock-downstream` stays fault-unaware).
3. **Worker's Phase 1 stub, fully removed.** `DownstreamProcessor` now makes
   a real HTTP call through `chaos-injector`. New `PermanentDownstreamException`
   resolves Phase 1's own deferred open question about non-retryable
   failures — `ERROR_4XX` chaos skips Spring Retry entirely and goes
   straight to `FAILED`.
4. **Full observability platform**: OpenTelemetry Java agent on all four
   services → a shared Collector → Grafana Tempo; `kube-prometheus-stack`
   (Prometheus + Alertmanager + Grafana + kube-state-metrics, node-exporter
   disabled) in a new `monitoring` namespace; the "PayGuard Overview"
   dashboard (11 panels); `PrometheusRule` + runbook for a payment-failure-rate
   alert.
5. **Autoscaling**: `metrics-server`, HPAs for `payment-api` (min 2/max 4)
   and `worker` (min 2/max 3), and the fix for the one genuinely new
   architectural problem this phase introduced — Argo CD's `selfHeal`
   (Phase 6) actively fights HPA-driven replica changes, since both look
   identical to Argo CD's diff engine. Fixed via `spec.ignoreDifferences`
   on `/spec/replicas`, scoped per-Deployment.
6. A `k6` load-generation script that reliably triggers real scaling.

Full rationale — including two rounds of user-directed revision (the
chaos-injector shape, and declining a colima resize in favor of a harder
resource trim) — in `docs/architecture/PHASE_7_DESIGN.md`.

## Surprises / gotchas

- **The architect caught its own bad assumption twice in one phase.** Its
  first draft assumed `kube-state-metrics` and `node-exporter` were
  interchangeable "last resort" trim candidates; re-checking against the
  dashboard's actual panel list (not the assumption) showed kube-state-metrics
  is the *sole* source of the HPA replica-count panels — this phase's
  headline new requirement — while node-exporter feeds nothing anything
  queries. Kept one, cut the other, for real reasons instead of a coin flip.
  This is the same "verify live state before designing around it" discipline
  Phase 6's architect first modeled.
- **GitOps changes how you iterate, not just how you deploy.** Every prior
  phase let us freely `kubectl apply`/`docker compose up` and test before
  ever committing. With Argo CD's `selfHeal: true` active, that stopped
  being true — any live-cluster testing that didn't match what's on `main`
  would just get reverted within seconds, exactly as Phase 6's own demo
  proved. We paused the Application's `syncPolicy.automated` for the
  duration of this phase's build-out (a live, temporary `kubectl patch`,
  never committed) so all six waves could be tested directly against the
  real cluster, then plan to re-enable it once this PR merges. Worth
  remembering for any future phase that touches `infra/k8s/` under an
  active GitOps setup: decide the iteration strategy *before* writing code,
  not after an implementer agent gets fought by `selfHeal` mid-task (which
  is exactly what happened to Wave 1's first attempt, before we paused
  sync).
- **The `k6` load test found a real capacity edge, not a bug.** Both HPAs
  scaled correctly under load and back down correctly after — the mechanism
  worked exactly as designed. But sustained peak (both HPAs at max replicas
  simultaneously, plus the full observability stack, plus Argo CD) pushed
  colima's single-node kind cluster to ~93% memory / 250-350% CPU, which
  made the **API server itself** transiently unresponsive and triggered a
  cascading liveness-probe restart storm across nearly every pod. Everything
  self-healed within about a minute with no permanent damage (one already-known
  race-condition artifact aside — see below). Given the choice to accept
  this as a documented characteristic or resize colima again, the decision
  was to accept it: the HPA mechanism itself is proven correct, and a
  resource-constrained single-node cluster genuinely destabilizing under
  its own worst-case load is, if anything, a fitting lesson for a project
  about noticing and surviving failure rather than hiding it. Documented
  explicitly in `docs/demos/PHASE_7_DEMO.md` so a future demo-runner expects
  this rather than panics.
- **The dual-write race from Phase 1 is real, not just theoretical.**
  Phase 1's design doc already flagged "the DB write and the stream publish
  are not atomic" as an accepted limitation. During this phase's load
  testing, that gap actually manifested: a payment got stuck permanently at
  `PENDING` because `worker` consumed its Redis Streams message and queried
  Postgres before `payment-api`'s transaction had actually committed —
  visible in worker's logs as `Payment {id} not found; skipping status
  update` for a payment that (per `payment-api`'s own response) did exist
  moments later. Rare — needs `worker` to win a very tight timing race,
  more likely to surface under real load or right after a pod restart when
  a worker aggressively drains a backlog. Not fixed this phase (frozen
  scope — `PaymentProcessingService`/`DownstreamProcessor` were Wave 1's
  job, not touched in later waves) since it's the same already-accepted gap
  from Phase 1, just the first time this project has actually watched it
  happen instead of only reading about it in a doc. Worth real
  consideration (an outbox pattern, or a short retry-on-not-found in the
  consumer) if this project ever wants payment delivery to be more than
  best-effort.
- **A subagent's own background-task handling produced a confusing, unhelpful
  final report once** (Wave 5). Rather than trust it, the orchestrator
  independently re-verified the entire alert-firing mechanism from scratch
  directly against the live cluster (forced chaos, sustained load for the
  full 2-minute evaluation window, watched the state transition
  `inactive` → `pending` → `firing` in real time, confirmed it reached
  Alertmanager) and found the actual implementation was correct — the
  problem was purely in how that one report was communicated, not the
  underlying work. Also found and cleaned up several stray leftover
  `kubectl port-forward` processes from that same session that were
  squatting on ports and producing misleading results (a 404 that looked
  like a broken deployment was actually a stale port-forward pointed at
  the wrong service). Worth remembering: a thin or confusing final report
  from a subagent is a signal to verify directly, not necessarily a signal
  the work itself is wrong.
- **`ADD <url>` in a Dockerfile defaults to root-only file permissions.**
  The OTel Java agent jar, fetched via Dockerfile `ADD` (chosen over
  `RUN curl`/`wget` since the Alpine runtime image has neither `curl` nor
  full `wget`), came in as mode `600` — unreadable by the non-root
  `payguard` user every service's runtime stage switches to, crashing the
  JVM with `Error opening zip file`. Fixed with an explicit `RUN chmod 644`
  before the `USER payguard` switch, in all four services' Dockerfiles.
- **`kube-prometheus-stack`'s `podMonitorSelector: {}` doesn't do what it
  looks like it does**, on its own. The chart's
  `podMonitorSelectorNilUsesHelmValues: true` default silently overrides an
  empty selector back to matching only `release: kube-prometheus-stack`-labeled
  PodMonitors — a well-known, sharp-edged gotcha with this chart. Needed an
  explicit `podMonitorSelectorNilUsesHelmValues: false` to actually watch
  PodMonitors across all namespaces as intended.
- **PodMonitor's default `job` label doesn't match hand-written dashboard
  queries.** Without an explicit `relabelings` rule forcing `job` to a
  literal service name, Prometheus's default job label for a PodMonitor
  target is `<namespace>/<name>` — every dashboard panel written against
  `job="payment-api"`-style queries would have silently returned nothing.

## What I'd do differently

Pause Argo CD's automated sync as the very first action of Wave 0, before
any implementer touches the cluster, rather than discovering the need for
it reactively after Wave 1's implementer got fought by `selfHeal`. The
signal was knowable in advance — any phase that plans to iteratively test
against `infra/k8s/` changes under an active GitOps Application needs an
iteration strategy decided up front, the same way Phase 6's own notes
already flagged proactive resource sizing as a lesson for "next time
you're about to add a lot of new pods."
