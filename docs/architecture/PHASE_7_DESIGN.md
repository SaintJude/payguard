# Phase 7 Design — Chaos, Observability, and Autoscaling

## Scope

This doc covers the full design for `docs/phase-notes/PHASE_7_TASKS.md`: a new
`mock-downstream` service (a simulated, fault-unaware payment processor) and
a new `chaos-injector` service — a standalone proxy `worker` calls instead of
`mock-downstream` directly, providing config-driven fault injection;
OpenTelemetry tracing across `payment-api`/`worker`/`chaos-injector`/
`mock-downstream` through a shared Collector into Grafana Tempo; Prometheus
metrics via `kube-prometheus-stack` with Grafana dashboards and one alert
rule + runbook; and `HorizontalPodAutoscaler`s for `payment-api`/`worker`
backed by `metrics-server`, a `k6` load-generation script, and the fix for
Argo CD's `selfHeal` fighting HPA-driven replica changes.

This is the largest phase to date by scope, so — unusually for this project's
design docs — it is also a **sequencing** document: see Recommended
Implementation Sequence below for the ordered waves an implementer (or
several, dispatched serially) should build in, not just the end-state
design.

This doc does **not** re-litigate anything already settled in
`docs/architecture/PHASE_1_DESIGN.md` (Redis Streams contract, schema
ownership), `PHASE_3_DESIGN.md` (Dockerfile pattern, base images, env-var
config), `PHASE_4_DESIGN.md` (CI matrix shape, lint tool), `PHASE_5_DESIGN.md`
(namespace, `imagePullPolicy`, resource requests/limits baseline, probes,
manifest file organization) or `PHASE_6_DESIGN.md` (Argo CD install, sync
policy, Kustomize-as-source) — it extends those decisions to cover four new
services (`mock-downstream`, `chaos-injector`, the OTel Collector, and the
observability platform) and one new cross-cutting mechanism (HPA vs. GitOps).

Out of scope: Terraform (Phase 8), any cloud provider dependency, a service
mesh, log aggregation (Loki or similar — logs stay as plain container stdout,
not piped through OTel or anywhere centralized this phase), and a
general-purpose chaos-engineering platform (chaos here stays a single,
manually-toggled fault switch confined to one dedicated proxy service — see
Decision §2).

## Concept Primer

### Distributed tracing, spans, and why a Collector sits in the middle

A single `POST /payments` request, once `mock-downstream` and
`chaos-injector` exist, touches at least five processes: `payment-api` (HTTP
handler + DB write + stream publish), Redis (the stream), `worker` (stream
consume + DB read/write), `chaos-injector` (the fault-injecting proxy hop),
and `mock-downstream` (the simulated processor call, reached only through
`chaos-injector`). Each of those hops generates its own log lines today, in
its own container, with no way to answer "show me everything that happened
for payment `abc-123`" without manually grepping five different `kubectl
logs` streams and lining up timestamps by eye.

**Distributed tracing** solves this by giving one request a single
**trace ID** that gets passed along every hop (an HTTP header,
`traceparent`, standardized by the W3C), and having each hop record a
**span** — "I started at T, did this piece of work, finished at T+Δ,
succeeded/failed" — tagged with that same trace ID. A tracing backend then
lets you pull up one trace ID and see every span across every service as a
single waterfall diagram: exactly which hop was slow, which hop errored, in
what order. This is the concrete payoff of OpenTelemetry auto-instrumentation
(Decision §3): the Java agent generates and propagates `traceparent` headers
and spans automatically for anything it recognizes (Spring MVC handlers,
outbound HTTP calls, JDBC calls, Redis calls) with zero application code
changes.

The **OpenTelemetry Collector** is a separate process that every
instrumented service sends its spans/metrics to (via the OTLP protocol,
gRPC on port 4317 by default), rather than each service talking directly to
a tracing backend. Why not skip it and point every service straight at the
trace backend? Because the Collector is a *pipeline*, not just a passthrough:
it batches spans before forwarding (fewer, larger network calls instead of
one call per span), and — the part that matters for a project that might
someday want more than one backend — it decouples "where do my services send
telemetry" (always: the Collector, forever) from "where does that telemetry
end up" (configurable in one place, the Collector's own config, without
touching any service). `CLAUDE.md`'s tech choices and `PROJECT_PLAN.md`'s
architecture diagram both already name the Collector as the intended shape;
this doc keeps that decision rather than reopening it.

### Prometheus's pull model, and what the Operator's CRDs are for

Prometheus works by **pulling** — on an interval (typically 15–30s), it
sends an HTTP GET to a `/metrics`-shaped endpoint on every target it's
configured to scrape, parses the plain-text response, and stores every
number as a time series. This is the opposite of what OTel does for traces
(services *push* spans to the Collector) — a deliberate, different design
choice Prometheus made for operational simplicity: a target doesn't need to
know Prometheus exists, doesn't need retry/buffering logic for a metrics
export failure, and Prometheus itself controls scrape cadence/backpressure
centrally.

Historically, telling Prometheus *which* targets to scrape meant hand-editing
a central `prometheus.yml` and reloading the server — painful in Kubernetes,
where pods come and go constantly. The **Prometheus Operator** (which
`kube-prometheus-stack`, Decision §4, is built on) fixes this by introducing
Kubernetes **Custom Resources** — `ServiceMonitor`, `PodMonitor`,
`PrometheusRule` — that you apply just like a Deployment or Service. The
Operator watches for these objects and automatically regenerates
Prometheus's scrape config to match, live, with no manual edit or reload.
A `PodMonitor` says "scrape any pod matching these labels, on this port
name, at this path"; a `PrometheusRule` says "evaluate this alerting
expression on a schedule." Both are ordinary YAML this doc's implementer
writes and commits — they just happen to be interpreted by the Operator
instead of core Kubernetes.

### The HPA control loop, and why it needs `metrics-server`

A `HorizontalPodAutoscaler` doesn't do anything by watching logs or events —
it polls a specific, narrow Kubernetes API (the **`metrics.k8s.io`
API**, aka the "resource metrics API") every ~15s, asking "what's the
current CPU/memory usage of the pods behind this Deployment, as a percentage
of what they *requested*?" If that percentage is above the HPA's configured
target, it computes a new desired replica count and directly calls the
`scale` subresource on the target Deployment — no different, mechanically,
from a human running `kubectl scale`.

Nothing serves the `metrics.k8s.io` API by default on a bare `kind` cluster
— that's `metrics-server`'s entire job: it scrapes every kubelet's local
`/stats/summary` endpoint (cAdvisor data, the same underlying source
Prometheus's own CPU/memory panels read from, just via a different path) and
re-serves it in the shape the HPA (and `kubectl top`) expect. This is why
`kubectl top nodes` currently returns `error: Metrics API not available` on
this cluster (confirmed live, see below) — `metrics-server` was never
installed, because nothing needed it before this phase.

**The GitOps conflict, restated precisely for HPA:** the HPA's `scale` call
and a human's `kubectl scale` call are *indistinguishable* to Argo CD — both
are just "someone changed `spec.replicas` outside of what Git declares."
Phase 6 already proved live that `selfHeal: true` reverts exactly this kind
of drift within seconds. An HPA that works for 10 seconds before Argo CD
reverts it back down is not a working HPA — this is the one mechanism this
phase's design *must* get right before the autoscaling demo means anything
(Decision §8).

### What "chaos engineering" means at this project's scale

Chaos engineering, as practiced at companies like Netflix (Chaos Monkey) or
via tools like Gremlin/Chaos Mesh, generally means: run controlled
experiments that inject real failure into a *production* system on a
schedule or continuously, to build confidence the system tolerates failure
before a real, unplanned outage does. That's a genuinely different thing
from what this phase builds. PayGuard's chaos injection is a **manually
toggled fault switch** on one internal service — you flip it on, watch what
happens, flip it off. There's no scheduler, no blast-radius controls, no
steady-state hypothesis engine, no automatic rollback. That's the right
scope for a learning project whose goal is "see the retry/backoff/idempotency
patterns actually get exercised by a failure," not "build a chaos platform"
— explicitly called out again in Decision §2 so it isn't quietly
over-built.

## Current live state (confirmed before writing this doc)

```
$ colima list
PROFILE  STATUS   ARCH     CPUS  MEMORY  DISK    RUNTIME  ADDRESS
default  Running  aarch64  4     6GiB    100GiB  docker

$ kind get clusters
payguard

$ kubectl get pods -n payguard
NAME                           READY   STATUS    RESTARTS       AGE
payment-api-868fbddd5d-6ntfp   1/1     Running   6 (5h44m ago)  6h8m
postgres-679d5b5896-dzrjj      1/1     Running   1 (5h45m ago)  6h8m
redis-76ccbcffdd-2zgq7         1/1     Running   1 (5h45m ago)  6h8m
worker-58f5f9b6b8-47764        1/1     Running   6 (5h44m ago)  6h8m
worker-58f5f9b6b8-p6l86        1/1     Running   0              12m

$ kubectl get application payguard -n argocd
NAME       SYNC STATUS   HEALTH STATUS
payguard   Synced        Healthy

$ kubectl top nodes
error: Metrics API not available
```

Colima is still at Phase 6's post-bump size (4 CPU / 6GiB), host has 10
cores/16GB per Phase 6's notes. `metrics-server` is confirmed absent. Worker
is running 2 replicas live even though nothing in this phase has touched it
yet (Phase 6's own GitOps demo bumped `infra/k8s/40-worker-deployment.yaml`
to `replicas: 2` and that's what's currently declared in Git — not drift).

## Recommended Implementation Sequence

Six waves, in order. Waves 2 and 4's *manifests* can be built in parallel
(no code dependency between them) but wave 4's *manual verification* step
must wait for wave 2 to exist — noted explicitly below rather than glossed
over.

| Wave | What | Why this position |
|---|---|---|
| **0 — Prep** | **No colima resize this phase** (user decision — stays at 4 CPU/6GiB). Instead: apply the trimmed `kube-prometheus-stack` values and `node-exporter`-disabled config (Decision §10) from the start, not retrofitted later, and confirm the current baseline (`kubectl top pods -n payguard` if available once `metrics-server` lands, or `docker stats` against the kind node container in the meantime) before adding any new pod, so any pressure that appears afterward is attributable to a specific wave, not a mystery. | Phase 6's retrospective said to size proactively rather than react to `Pending` pods — with no resize available this time, "proactive" means going in with a trimmed configuration and a known starting baseline instead of hoping default chart values fit. |
| **1 — `mock-downstream` + `chaos-injector` + worker's network hop + instrumentation scaffolding** | Build **both** `mock-downstream` and `chaos-injector` (Dockerfile, k8s manifests, Helm entries, CI matrix entries) — `chaos-injector` as a passthrough proxy defaulting to `NONE` (forwards everything to `mock-downstream` unmodified); rewire `worker`'s `DownstreamProcessor` to call `chaos-injector` over HTTP, replacing the Phase 1 in-process stub; add `spring-boot-starter-actuator` + Micrometer + the OTel Java agent to **all four** services now, even though nothing collects/scrapes yet. | Both new services are prerequisites for anything chaos-related to be observable, and both are simple enough (one's a thin simulated processor, the other a thin proxy) to build together in one pass rather than staggered. Adding instrumentation dependencies at service-creation time is one mechanical pass across four services; retrofitting it in a later wave would be four separate, riskier passes. Verify with the existing happy-path test — the payment flow must still complete end-to-end, just via two real network hops now instead of a stub. |
| **2 — Observability platform stand-up** | OTel Collector, Grafana Tempo, `kube-prometheus-stack` trimmed per Decision §10 (Prometheus + Alertmanager + Grafana + kube-state-metrics — `node-exporter` disabled), `PodMonitor`s, Grafana datasources + the one dashboard. | Nothing worth looking at exists until wave 1's real network hops exist (a trace with no downstream call is a weak demo) — but this must exist **before** wave 3 (chaos) and wave 4 (autoscaling verification), because both of those waves' own manual-verification steps require "see it in Grafana/Tempo" to already be possible. Building chaos or HPA blind, then coming back to wire up dashboards afterward, means redoing the verification pass. |
| **3 — Chaos injection** | Give `chaos-injector`'s scaffolded passthrough logic real fault behavior (`LATENCY`/`ERROR_5XX`/`ERROR_4XX`/`DROP`/`NONE`) plus a live `/chaos/config` endpoint. | Needs wave 1's real network hops (something to inject faults into, and a proxy already sitting in the path) and wave 2's dashboards/traces (somewhere to *see* the fault land) — sequencing it any earlier means building a feature you can't verify. |
| **4 — Autoscaling** | `metrics-server`, HPA manifests, the Argo CD `ignoreDifferences` fix, the `k6` load script. | **Manifests have no dependency on waves 1–3** — this could be built in parallel with wave 2 by a second implementer if this project ever runs waves concurrently. Flagged explicitly rather than forcing a false linear dependency. What *does* have to wait is verification: "watch the scale event on the Grafana dashboard" (this phase's explicit new requirement, not just `kubectl get pods`) needs wave 2's replica-count panel to exist first. |
| **5 — Alert rule + runbook** | `PrometheusRule` for the payment-failure-rate alert, `docs/runbooks/payment-failure-rate.md`. | Needs wave 1's `payments_processed_total` metric, wave 2's Alertmanager, and wave 3's chaos toggle to force-trigger it for verification — the most downstream wave, correctly last. Independent of wave 4; could run in parallel with it. |
| **6 — Wrap-up** | Full three-category manual verification pass per `PHASE_7_TASKS.md`, `PHASE_7_NOTES.md`, `PHASE_7_DEMO.md`, README/CLAUDE.md updates, commit. | Standard per `CLAUDE.md`'s workflow. |

This intentionally answers the prompt's own open question ("observability
before chaos, or chaos before observability?") with **observability before
chaos is exercised, but after the service chaos will act on exists** — waves
1 → 2 → 3, not 1 → 3 → 2, and not "chaos and observability as one
undifferentiated wave."

## Decision

### 1. `mock-downstream` service

**Language/framework**: Java 21 / Spring Boot / Maven, matching
`payment-api`/`worker` exactly. No real reason to deviate — it gets the same
Dockerfile pattern, the same CI matrix treatment, and the same
actuator/Micrometer/OTel-agent instrumentation approach for free, and a
different language would mean a second toolchain to maintain for one small
HTTP service.

**Worker's relationship — replaces the Phase 1 stub, not additive to it, and
now points at `chaos-injector`, not `mock-downstream`, directly.**
`worker`'s `DownstreamProcessor.process(UUID paymentId)` currently throws a
hardcoded `TransientDownstreamException` on the *first* call per `paymentId`
(an in-memory counter, Phase 1 Decision §4) purely to give Spring Retry
something to retry in the absence of any real chaos mechanism. Now that a
real chaos mechanism exists, that synthetic failure is redundant — worse,
leaving both in place would mean two independent, uncoordinated failure
sources fighting over the same retry counter, undermining the ability to
reason about "did this fail because of chaos I configured, or because of the
Phase 1 stub's own logic." **`DownstreamProcessor` is rewritten to make a
real HTTP call to `chaos-injector` via Spring's `RestClient`, and the
Phase-1 hardcoded per-payment failure is removed entirely.** `worker` never
calls `mock-downstream` itself — per Decision §2, `chaos-injector` is a
standalone proxy service sitting between them, and `mock-downstream` is only
ever reached by `chaos-injector` forwarding a request through. Under
`chaos-injector`'s default (`NONE`) chaos mode, every call passes straight
through to `mock-downstream` and succeeds — the happy path is deterministic
again, same as it was before chaos existed to inject uncertainty into it.
`mock-downstream` itself carries **no chaos logic of its own** — it is a
boring, always-succeeds simulated processor; every fault-injection decision
now lives in one place, `chaos-injector`, which is a cleaner separation of
concerns than routing chaos-awareness through the same service that's
supposed to be "the real processor."

**Spring Retry's fate: kept, retargeted at real HTTP outcomes from
`chaos-injector`.** The `@Retryable`/`@Recover` structure on
`PaymentProcessingService` from Phase 1 is exactly the right shape for this
— it doesn't move or get removed, it starts firing for real reasons instead
of a synthetic one:

| Outcome from `chaos-injector` | Mapped to | Retried? |
|---|---|---|
| `200 OK` (either a `NONE`-mode passthrough, or a `LATENCY`-mode delayed-but-successful passthrough, both ultimately answered by `mock-downstream`) | success | — |
| `503` (chaos `ERROR_5XX` mode — `chaos-injector` short-circuits, `mock-downstream` never sees the call) | `TransientDownstreamException` | Yes — same `@Retryable(maxAttempts=3, backoff=200ms×2)` as Phase 1 |
| Connection refused / read timeout (chaos `LATENCY` mode exceeding worker's timeout while forwarding, or `DROP` mode) | `TransientDownstreamException` | Yes |
| `400` (chaos `ERROR_4XX` — new — `chaos-injector` short-circuits) | `PermanentDownstreamException` (new) | **No** — goes straight to `@Recover`-equivalent handling, sets `FAILED` immediately |

The `400`/`PermanentDownstreamException` path is new scope, not present in
Phase 1. It directly resolves Phase 1's own deferred Open Question ("FAILED
means retries exhausted, full stop... that distinction becomes real once
Phase 7's chaos injector can return genuinely permanent errors" —
`PHASE_1_DESIGN.md` lines 164–172). Phase 1 already left room for exactly
this by scoping `@Retryable` to one specific exception type rather than
"anything thrown" — this decision cashes that groundwork in rather than
introducing a new pattern. Confirmed in scope for this phase (see the
resolved Open Question below).

**Minimal API surface** (unchanged from `mock-downstream`'s own perspective
— it has no awareness that `chaos-injector` sits in front of it):
- `POST /v1/process` — body `{"paymentId": "<uuid>", "amount": <number>}`.
  Always: `200 {"paymentId": "<uuid>", "status": "PROCESSED"}` — no failure
  modes, no chaos config, no `/chaos/config` endpoint. `mock-downstream` is
  deliberately the "boring" half of this pair.
- `GET /actuator/health`, `GET /actuator/prometheus` — on the management
  port (Contracts).

No persistence, no database, no queue, and — now that chaos logic has moved
to `chaos-injector` entirely — no in-memory state of any kind.
`mock-downstream` is fully stateless.

### 2. `chaos-injector`

**Decision (revised per user direction): a standalone proxy service,
`chaos-injector`, sitting between `worker` and `mock-downstream` — not an
in-process lib inside `mock-downstream`, and not a sidecar.** `worker` calls
`chaos-injector`'s `/v1/process`; `chaos-injector` either forwards the
request to `mock-downstream` unmodified (passthrough), forwards it with
injected delay, or short-circuits it with a synthetic faulty response before
`mock-downstream` ever sees the call, depending on its own live-configured
chaos state. This is a real, standalone Java 21/Spring Boot service — same
Dockerfile pattern, same actuator/Micrometer/OTel-agent instrumentation, same
CI matrix treatment as every other service in this project (Decision §1's
reasoning for `mock-downstream`'s stack choice applies identically here: no
reason to reach for a second language/toolchain for one small HTTP service).

This confirms `PHASE_7_TASKS.md`'s literal phrasing ("Both get Dockerfiles +
k8s manifests") — `chaos-injector` **is** its own deployable, exactly as
that checklist assumed. `PROJECT_PLAN.md`'s own service table had hedged
this ("`chaos-injector` | ... | Sidecar or **lib**"), and this doc's first
draft picked "lib"; the user has since chosen the third option this doc's
own Alternatives table already listed — a standalone proxy — over both.

**Why standalone-service over in-process-lib, now that it's the confirmed
choice**: a separate process is a *real* intermediate network hop — it can
actually add wall-clock latency, actually hold a TCP connection open past a
client timeout, actually return a distinct HTTP response before the real
processor is ever called — none of which an in-process lib inside
`mock-downstream` can claim, since there "chaos" and "the processor's real
answer" both happen inside the same JVM, one function call apart. That gap
matters for a project whose explicit goal is to make failure *feel* real
enough to justify the retry/backoff machinery being exercised. It also gives
a cleaner separation of concerns (Decision §1): `mock-downstream` stays a
simple, honest simulated processor with zero chaos-awareness in its own
code, and every fault-injection decision lives in exactly one place.

**Why not the sidecar option specifically** (the other "separate" option
this doc's Alternatives table carried): a sidecar approach (e.g. a small
Envoy/nginx proxy container in `mock-downstream`'s own pod, faults injected
at the proxy layer) is the same category of thing `CLAUDE.md` explicitly
rules out for this project — "no need for a service mesh" — since fault
injection via a proxy's own config language (Envoy's `fault` filter, nginx
`error_page`/`limit_req` tricks) is exactly the mechanism real service
meshes use. A plain Spring Boot HTTP service the implementer writes and
fully understands, using the same stack as every other service in this
repo, teaches the target lesson (retry/backoff against real network
failure) without introducing a second, unrelated tool's configuration
language on top of everything else this phase already adds.

| Option | What it looks like | Pros | Cons |
|---|---|---|---|
| **Standalone proxy service (chosen)** | A fourth Deployment `worker` calls instead of `mock-downstream` directly, which forwards (or doesn't) to the real one | Most realistic simulation of *external network* chaos (a real intermediate hop can actually drop/delay packets, not just simulate it in code); reusable in front of any future downstream service; matches every other service's stack/Dockerfile/CI pattern | A whole new Dockerfile/manifest/CI-matrix/instrumentation surface, and a fifth hop in every trace — accepted as the right tradeoff for realistic chaos simulation |
| In-process lib inside `mock-downstream` (this doc's original recommendation, not chosen) | A filter/interceptor that runs before every `/v1/process` handler, consulting in-memory chaos state | Zero new Dockerfile, k8s manifests, Service, or instrumentation target; zero new network hop to reason about; a few dozen lines of code | Chaos and "the real answer" happen one function call apart in the same JVM — can't simulate an actual dropped/delayed *network* hop, only an application-level decision that looks similar from the outside |
| Sidecar container (e.g. a small Envoy/nginx proxy in the same pod, faults injected at the proxy layer) | `mock-downstream`'s pod gets 2 containers; worker still calls one Service, but traffic is intercepted by the sidecar before reaching the app container | Closer to how real service meshes (Istio, Linkerd) do fault injection — genuinely transferable skill | Requires learning a proxy's own fault-injection config language on top of everything else this phase already introduces; `CLAUDE.md` explicitly rules out a service mesh for this project |

**Configuration/toggle mechanism**: a small in-memory REST admin surface on
`chaos-injector` itself — `GET`/`PUT /chaos/config` (moved here from
`mock-downstream`, which now has no chaos awareness of its own) — rather
than a ConfigMap requiring a pod restart to take effect, or a general rules
engine. Reasoning: the manual-verification goal is "trigger a configured
fault and watch it happen," which is far more pleasant to do live (`curl -X
PUT .../chaos/config -d '{"mode":"ERROR_5XX", ...}'`, watch dashboards react
within seconds, `curl` it back to `NONE`) than via a ConfigMap edit +
`kubectl rollout restart` cycle every time. A `CHAOS_MODE` env var (sourced
from the ConfigMap, consistent with the existing config pattern) still sets
the *default on pod start* (`NONE` — chaos-off by default, so a normal
`kubectl apply`/Argo CD sync never silently starts injecting faults), but
live toggling during a demo goes through the REST endpoint. This is
deliberately **not** a rules engine, scheduler, or probability-weighted
multi-fault mix — one active mode, one or two numeric params — per
"don't over-engineer."

**Fault modes** (exactly five, exhaustive for this phase — `ERROR_4XX`
confirmed in scope per the resolved Open Question below):

| Mode | Behavior | Worker sees |
|---|---|---|
| `NONE` (default) | Forwards to `mock-downstream` unmodified, returns its real `200` | Success |
| `LATENCY` | Sleeps `latencyMs` (configurable) before forwarding to `mock-downstream`, then returns its real response | Slow success — may or may not exceed worker's own timeout depending on configured value |
| `ERROR_5XX` | Short-circuits with probability `probabilityPct` (0–100) — returns `503` immediately **without ever calling `mock-downstream`**; else forwards normally | `TransientDownstreamException` on the short-circuited branch |
| `ERROR_4XX` | Short-circuits with probability `probabilityPct` — returns `400` immediately **without ever calling `mock-downstream`**; else forwards normally | `PermanentDownstreamException` on the short-circuited branch — not retried |
| `DROP` | Sleeps longer than worker's configured read timeout (see Contracts) without ever forwarding to `mock-downstream` or responding | A client-side timeout — the practical way to simulate a dropped connection over synchronous HTTP without raw socket manipulation; functionally identical to `LATENCY` set past the timeout threshold, but named separately because "the connection died" and "it was just slow" are different concepts worth being able to point at explicitly during the chaos demo |

### 3. OpenTelemetry

**Instrumented services**: `payment-api`, `worker`, `chaos-injector`,
`mock-downstream` — all four. (`chaos-injector` is now a real, separate
process per Decision §2's revised standalone-service choice, so it needs
the same instrumentation treatment as every other service — it's a genuine
hop in every trace, not something the agent would otherwise see.)

**Auto-instrumentation (Java agent), not manual SDK.** The OTel Java agent
(`opentelemetry-javaagent.jar`, attached via `-javaagent:`) auto-instruments
Spring MVC request handling, outbound HTTP clients (`RestClient`, which
delegates to the JDK `HttpClient` the agent already instruments), JDBC
(Postgres), and Lettuce (Redis) — with **zero application code changes** —
and automatically propagates `traceparent` across every one of those hops,
including the Redis Streams handoff between `payment-api` and `worker` (the
agent's Lettuce instrumentation captures the `XADD`/`XREADGROUP` calls as
spans, though it cannot itself bridge trace context *through* a message
payload the way it bridges an HTTP header — see Open Questions). Manual SDK
instrumentation would give finer control (custom span names/attributes,
business-meaningful span boundaries around the hand-rolled stream-consumer
loop) but means touching every service's code and is more surface area to
get wrong, for a phase that's already the largest to date — now touching
four services instead of three. **Recommendation:
agent now; one optional manual span around `PaymentStreamConsumer`'s poll
loop (which is hand-rolled, not a Spring-managed abstraction the agent
recognizes) is worth adding later if the auto-instrumented trace ever proves
too coarse — not required for this phase's Definition of Done.**

Cost worth naming: the agent adds real JVM startup time and roughly
50–100MiB of extra heap/off-heap overhead per instrumented pod from its own
bytecode-instrumentation machinery — factored into the resource budget
(Decision §10).

**What gets exported, and where**: **traces only** go through the OTel
pipeline (agent → Collector → Tempo). **Metrics do not** — Spring Boot
Actuator + `micrometer-registry-prometheus` already gives every service a
zero-friction `/actuator/prometheus` endpoint Prometheus can scrape
directly (Decision §4), which is simpler than also standing up OTel's metrics
SDK + an OTLP metrics pipeline + a Prometheus *exporter* on the Collector to
achieve the identical end state. Routing metrics through the Collector too
would be a legitimate, more "everything through one pipeline" design — reasonable
in a real production system consolidating multiple metrics ecosystems — but
adds a second way to get the same numbers into Prometheus for no benefit
here. **Logs stay as plain container stdout** — no OTel logs pipeline, no
Loki — out of scope, see Out of Scope.

**Collector deployment shape: one shared Deployment + Service in the
`payguard` namespace**, not a sidecar per pod. A shared Collector means one
place to configure the pipeline (receivers/processors/exporters), one set of
resource requests instead of three duplicated ones, and batching that
actually batches across services instead of each sidecar batching alone.
The alternative (sidecar-per-pod) is the standard choice when you need
*per-node* processing (e.g. enriching spans with node-local metadata via a
DaemonSet) — not a need this single-node cluster has, and now that
`chaos-injector` is a fourth real process, a sidecar-per-pod shape would mean
4 sidecars instead of 3. All four instrumented services point at the
Collector via one shared config value,
`OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317` (in-namespace
short DNS name, since the Collector lives alongside the app in `payguard`).

**Trace backend: Grafana Tempo (single-binary mode)**, added to the
`monitoring` namespace via a small, minimal Helm install alongside
`kube-prometheus-stack`. This is an **addition beyond what `CLAUDE.md`'s
Tech Choices section currently lists** ("Observability: Prometheus +
Grafana + OpenTelemetry Collector" — no trace store named) — flagged
explicitly rather than silently assumed, see Open Questions. It's necessary
because `PROJECT_PLAN.md`'s own Learning Objective #5 says "instrument
services with OpenTelemetry **and visualize them in Grafana**" — traces
without a queryable backend can't be visualized, only grepped from Collector
pod logs. Tempo specifically (over Jaeger) because Grafana ships a native
Tempo data source with trace-view panels and trace-to-metrics correlation
built in, keeping this phase's "one pane of glass" story intact — no second
UI (Jaeger's own) to context-switch to. Tempo's single-binary mode needs no
object-store/Cassandra/Elasticsearch backing — local disk is sufficient for
this project's trace volume — so it's a comparably light addition, not a
new heavyweight subsystem.

### 4. Prometheus

**Decision: `kube-prometheus-stack`** (the `prometheus-community` Helm
chart bundling the Prometheus Operator, Prometheus server, Alertmanager,
Grafana, node-exporter, and kube-state-metrics — **node-exporter disabled**
in this project's values, kube-state-metrics kept, see the values sketch and
Decision §10 for why), installed via plain `helm install` into a dedicated
`monitoring` namespace — **not** hand-rolled, and **not** GitOps-managed by
the existing `payguard` Argo CD Application.

This reasons through the prompt's own steer rather than reflexively
reapplying Phase 5's "hand-write it" precedent to a differently-shaped
decision, explicitly: Phase 5 rejected `kompose` because auto-generating
*this project's own application manifests* would hide *this project's own*
decisions (the ConfigMap/Secret split, the namespace layout, which values
plausibly vary between deployments) behind a generator — decisions an
implementer needs to have actually made and understood. Installing
`kube-prometheus-stack` is a different kind of decision: it's not
generating PayGuard's own manifests at all, it's installing a **well-known
third-party observability platform**, exactly analogous to how Phase 6
installed Argo CD itself from its own plain upstream install manifest
rather than hand-writing an Argo-CD-equivalent from scratch. Hand-rolling
Prometheus + Alertmanager + Grafana + a scrape-config-reload story from
first principles would mean reproducing the Prometheus Operator's CRD
machinery by hand (a nontrivial controller) for zero PayGuard-specific
learning payoff — the actually transferable, real-world skill here is
"install and configure the community-standard chart, then write your own
`PodMonitor`/`PrometheusRule` CRDs against it," which is exactly what this
design does. `kube-prometheus-stack` **is** the standard real-world answer
for this exact problem — using it is the "don't over-engineer" call, not
the over-engineered one.

**What stays hand-written and Argo CD-managed** (the actual PayGuard-specific
decisions, living in `infra/k8s/` alongside everything else): the
`PodMonitor` CRDs telling Prometheus what to scrape, the `PrometheusRule`
CRD defining this phase's one alert, and the Grafana dashboard ConfigMap.
The platform is imperative-installed once (like Argo CD's own bootstrap);
the application's *use* of that platform is GitOps'd like everything else.

**Values overrides** (illustrative, not written to disk by this doc — see
Decision §10 for the full resource-budget reasoning behind these numbers,
which are trimmed harder than an earlier draft of this doc, since colima is
staying at 4 CPU/6GiB rather than being resized):

```yaml
# infra/observability/kube-prometheus-stack-values.yaml (illustrative)
prometheus:
  prometheusSpec:
    podMonitorNamespaceSelector: {}   # watch PodMonitors in ALL namespaces,
    podMonitorSelector: {}            # not just `monitoring` — payguard's
                                       # PodMonitors live in the `payguard`
                                       # namespace (see Contracts)
    resources:
      requests: { cpu: 50m, memory: 200Mi }
      limits:   { cpu: 300m, memory: 400Mi }
    retention: 3h                     # trimmed further from an earlier 6h —
                                       # laptop demo session, not a
                                       # production TSDB; WAL/series memory
                                       # scales with retention
prometheusOperator:
  resources:
    requests: { cpu: 50m, memory: 64Mi }
    limits:   { cpu: 100m, memory: 128Mi }
alertmanager:
  alertmanagerSpec:
    resources:
      requests: { cpu: 25m, memory: 32Mi }
      limits:   { cpu: 50m, memory: 64Mi }
grafana:
  resources:
    requests: { cpu: 50m, memory: 96Mi }
    limits:   { cpu: 150m, memory: 192Mi }
  sidecar:
    dashboards:
      enabled: true
      searchNamespace: ALL            # so it finds the PayGuard dashboard
                                       # ConfigMap living in `payguard`, not
                                       # `monitoring`
nodeExporter:
  enabled: false                      # disabled — see Decision §10: nothing
                                       # in this phase's dashboard (Decision
                                       # §5) queries node-exporter metrics;
                                       # per-pod CPU/memory panels read from
                                       # cAdvisor via kubelet instead, and
                                       # this chart ships node-exporter as a
                                       # DaemonSet regardless of pod count,
                                       # making it pure overhead here
kube-state-metrics:
  resources:
    requests: { cpu: 50m, memory: 128Mi }
    limits:   { cpu: 100m, memory: 256Mi }
                                       # NOT a trim candidate despite being
                                       # flagged as one in an earlier draft —
                                       # this is the sole source of the HPA
                                       # replica-count panels (Decision §5),
                                       # this phase's single most important
                                       # new dashboard requirement; disabling
                                       # it would silently break the
                                       # autoscaling visibility the whole
                                       # phase is partly about proving
defaultRules:
  create: false                       # skip the chart's large bundle of
                                       # generic Kubernetes alert rules —
                                       # this phase wants exactly one,
                                       # PayGuard-authored rule to be legible,
                                       # not buried in 50 pre-built ones
```

### 5. Grafana dashboards

One dashboard, "PayGuard Overview," provisioned as a Kubernetes ConfigMap
(labeled `grafana_dashboard: "1"`, picked up automatically by the chart's
Grafana sidecar) living in `infra/k8s/` — Argo CD-managed like the rest of
the app. Panel list, organized in three rows:

| Row | Panel | Query shape | Answers |
|---|---|---|---|
| Resources | CPU usage per pod (payment-api, worker, mock-downstream, chaos-injector, postgres, redis) | `rate(container_cpu_usage_seconds_total{namespace="payguard"}[1m])` by `pod` | Per-service resource stats (builds on Phase 5's requests/limits); sourced from cAdvisor via kubelet, **not** node-exporter — unaffected by node-exporter being disabled (Decision §10) |
| Resources | Memory usage per pod, same 6 workloads | `container_memory_working_set_bytes{namespace="payguard"}` by `pod` | Same |
| Traffic | `payment-api` HTTP request rate by status code | `sum(rate(http_server_requests_seconds_count{job="payment-api"}[1m])) by (status)` | Request volume |
| Traffic | `payment-api` p95 latency | `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{job="payment-api"}[5m])) by (le))` | Latency under load |
| Traffic | Payments processed rate by outcome | `sum(rate(payments_processed_total[1m])) by (status)` | Transaction volume, the metric this phase's requirement explicitly asks for |
| Traffic | Payment failure rate % (gauge, threshold line at 5%) | Same expression as the alert rule (Decision §6) | Direct visual of the alert's own condition |
| Chaos | `chaos-injector` request rate, split by outcome (passthrough `200` vs. short-circuited `503`/`400` vs. `DROP`-induced timeout) | `sum(rate(http_server_requests_seconds_count{job="chaos-injector"}[1m])) by (status)` | The primary point to observe injected faults — `chaos-injector` is now the sole place fault decisions are made, since `mock-downstream` itself is fault-unaware (Decision §1/§2) |
| Chaos | `mock-downstream` request rate (secondary) | `sum(rate(http_server_requests_seconds_count{job="mock-downstream"}[1m])) by (status)` | How much traffic actually reaches the real processor vs. gets short-circuited by `chaos-injector` before ever arriving — the gap between this panel and the one above *is* the chaos being injected |
| Scaling | HPA current vs. desired replicas, `payment-api` and `worker` | `kube_horizontalpodautoscaler_status_current_replicas` / `..._desired_replicas` | The autoscaling requirement's core ask: see the scale event, not infer it — sourced from kube-state-metrics, kept explicitly for this (Decision §10) |
| Scaling | Actual running replica count (ground truth) | `kube_deployment_status_replicas{namespace="payguard"}` by `deployment` | Corroborates the HPA panel with real pod counts — also kube-state-metrics |
| Scaling | `payment-api` CPU utilization % vs. HPA target (50%) static reference line | `avg(rate(container_cpu_usage_seconds_total{namespace="payguard",pod=~"payment-api.*"}[1m])) / 0.1` (denominator is the known, static 100m CPU request per pod — a hardcoded reference rather than a `kube_pod_container_resource_requests` query, keeping this one panel simple) | Explains *why* it scaled, not just that it did |

### 6. Alert rule + runbook

**Rule: payment failure rate, not raw HTTP 5xx.** `PROJECT_PLAN.md`'s own
example ("error rate > 5% for 2 minutes") is confirmed as the right shape,
but retargeted at what's actually meaningful in this architecture:
`payment-api`'s own HTTP surface almost never returns a 5xx under chaos,
because `POST /payments` returns `202` immediately and processing happens
asynchronously in `worker` — chaos injected at `mock-downstream` shows up as
elevated **payment failure rate** (`payments_processed_total{status="FAILED"}`,
meaning retries were exhausted), not as `payment-api` HTTP errors. Alerting
on the business-meaningful signal (payments actually failing, after retry)
is also more actionable for an on-call than alerting on `mock-downstream`'s
raw error rate one layer removed — a payment platform's alert should say
"payments are failing," and let dashboards/traces (already built in wave 2)
answer "why."

```yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: payguard-alerts
  namespace: payguard
  labels:
    release: kube-prometheus-stack   # required — see the sharp-edge note below
spec:
  groups:
    - name: payguard.rules
      rules:
        - alert: PaymentFailureRateHigh
          expr: |
            (sum(rate(payments_processed_total{status="FAILED"}[1m]))
             /
             sum(rate(payments_processed_total[1m]))) > 0.05
          for: 2m
          labels:
            severity: warning
          annotations:
            summary: "Payment failure rate above 5% for 2 minutes"
            description: "{{ $value | humanizePercentage }} of payments are failing after retries exhausted. Check chaos-injector's chaos config and recent traces."
            runbook_url: "https://github.com/SaintJude/payguard/blob/main/docs/runbooks/payment-failure-rate.md"
```

**Sharp edge worth flagging explicitly**: the Prometheus Operator's
`Prometheus` custom resource only picks up `PrometheusRule` objects matching
its configured `ruleSelector` — by default, `kube-prometheus-stack` wires
this to match a `release: <helm-release-name>` label. A `PrometheusRule`
without that exact label silently does nothing (no error, the rule just
never evaluates) — a well-known, easy-to-lose-an-hour-to gotcha with this
chart, worth an implementer knowing about before wondering why the alert
never fires.

**Where it fires: Alertmanager**, via the CRD above (evaluated by
Prometheus, routed to the Alertmanager `kube-prometheus-stack` already
bundles), not Grafana's own newer unified alerting — using both would be
two competing alerting systems watching the same data for one alert; since
Alertmanager is already installed as part of the chosen stack (Decision
§4), it's the lower-ceremony choice. No real notification receiver is
configured (no Slack/PagerDuty/email — no operational audience exists for a
solo learning project, consistent with Phase 6's identical Out-of-Scope
call on Argo CD Notifications) — the alert is "observable" via the
Alertmanager UI (`kubectl port-forward svc/alertmanager-operated -n
monitoring 9093`) and via Grafana, which the chart wires to read
Alertmanager as a data source automatically, so firing alerts also show up
in Grafana's own Alerting view with no extra config.

**Runbook: `docs/runbooks/payment-failure-rate.md`** (this is literally the
directory `PROJECT_PLAN.md`'s target repo structure already names,
`docs/runbooks/` — first phase to actually populate it). Shape: alert
name/summary at the top, what it means in one paragraph, a direct link to
the "PayGuard Overview" dashboard's failure-rate panel, diagnostic steps
(check `GET http://chaos-injector:8080/chaos/config` for an active chaos
mode; check the failure-rate and chaos-injector-error-rate panels for
correlation; pull a recent failing trace in Tempo by payment ID from a
`FAILED` row), remediation (if chaos was intentionally left on: `PUT
/chaos/config` back to `NONE` against `chaos-injector`; if not a deliberate
test: check `mock-downstream`'s own pod logs/health for a real problem,
since a genuine failure there would mean `chaos-injector` is forwarding
correctly but the real processor is unhealthy), and a resolution/close note
(alert self-resolves once the rate drops back under 5% for the same
2-minute window).

### 7. `metrics-server` + HPA

**`metrics-server`**: standard install manifest
(`kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml`),
imperative like Argo CD's own bootstrap — **plus the well-known kind-specific
patch**: kind's kubelet serving certs aren't signed in a way
`metrics-server` trusts by default, so its Deployment needs
`--kubelet-insecure-tls` added to its container args, or every scrape fails
with an x509 error and the HPA silently never gets data (`kubectl describe
hpa` would show `<unknown>` for current metrics forever). This is a
near-universal kind gotcha, worth calling out explicitly rather than
letting an implementer discover it via a confusing failure.

**HPA target: `payment-api` is the primary demo target; `worker` gets an
HPA too, for completeness/practice, but is explicitly *not* the focus of
this phase's load-test verification.** Reasoning: `payment-api` scaling is
directly, reliably demonstrable — a load-generation tool driving `POST
/payments` traffic directly saturates its CPU. `worker` scaling is
**legitimate** (Phase 5's Open Questions already confirmed Redis Streams
consumer groups make scaling workers safe — each message still goes to
exactly one consumer regardless of replica count) but **harder to
convincingly trigger via CPU utilization specifically**: worker's actual
per-message work is mostly waiting (on Redis `BLOCK`, on the HTTP call to
`mock-downstream`, on Spring Retry's backoff `sleep`s) rather than
CPU-bound computation — a thread parked in `Thread.sleep` or blocked on I/O
barely registers on a CPU utilization metric. Driving worker's CPU up
enough to cross a utilization threshold would need either an
unrealistically high message volume or made-up CPU-bound work with no
teaching value. This is stated plainly rather than glossed over: worker's
HPA is real, correctly configured, and included, but the load-test
demo's guaranteed, reliable scale-up is `payment-api`'s.

```yaml
# infra/k8s/80-payment-api-hpa.yaml (illustrative)
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: payment-api
  namespace: payguard
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: payment-api
  minReplicas: 2
  maxReplicas: 4
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 50
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 60   # avoid flapping on brief traffic dips
```

`worker`'s HPA is identical in shape: `minReplicas: 2`, `maxReplicas: 3`,
`averageUtilization: 60` (slightly more tolerant, given the CPU-signal
weakness noted above).

**`minReplicas: 2` for `payment-api`, up from the Git-declared
`replicas: 1`** — a small, explicitly-called-out change the implementer
should make in the same pass as adding the HPA
(`infra/k8s/30-payment-api-deployment.yaml`'s `replicas: 1` → `2`), so the
very first Argo CD sync after this wave doesn't briefly apply "1" and then
have the HPA immediately correct it to 2 — avoids a confusing transient
state on the first demo run.

**`maxReplicas: 4` for `payment-api` (down from an earlier draft's `6`) and
`maxReplicas: 3` for `worker` (down from `4`) — both reduced directly because
of the no-colima-resize decision (Decision §10).** With more headroom
available (the original 6 CPU/10GiB plan), a 5-pod burst for `payment-api`
alone fit comfortably; staying at 4 CPU/6GiB means every extra replica at
peak is real, scarce memory, so the ceilings are sized against the tighter
budget in Decision §10 rather than picked for demo drama. `minReplicas: 2` →
`maxReplicas: 4` still gives a clearly visible 2× scale-up during the load
test (the dashboard's replica-count panel shows a real step change, not a
1-replica blip) while keeping the worst-case burst small enough to fit
Decision §10's numbers with real margin.

### 8. The Argo CD / HPA conflict fix

**Confirmed: `spec.ignoreDifferences` with `jsonPointers: [/spec/replicas]`,
scoped per-Deployment, in `infra/argocd/application.yaml`.** This is the
standard, well-documented Argo CD mechanism for exactly this conflict — it
tells Argo CD's diff engine "don't consider a live value at this JSON path
to be drift, no matter what Git says," scoped narrowly enough that
`selfHeal` still fully protects every *other* field (image tag, env vars,
resource limits, container args) on the same Deployment. This is a much
more precise fix than disabling `selfHeal` globally (which would throw away
Phase 6's entire drift-protection story for the sake of one field on two
Deployments).

```yaml
# infra/argocd/application.yaml — add under spec: (illustrative, exact
# addition the implementer makes to the existing file)
spec:
  ignoreDifferences:
    - group: apps
      kind: Deployment
      name: payment-api
      namespace: payguard
      jsonPointers:
        - /spec/replicas
    - group: apps
      kind: Deployment
      name: worker
      namespace: payguard
      jsonPointers:
        - /spec/replicas
```

Nothing else in `infra/argocd/application.yaml` changes — `syncPolicy` stays
exactly as Phase 6 left it (`prune: true`, `selfHeal: true`).

### 9. Load-generation tool

**Decision: `k6`** (`brew install k6`), scripted in JS, using a
`ramping-arrival-rate` executor — not `hey`/`vegeta`/`ab`. The deciding
factor is specific to this app's semantics, not a generic tool preference:
`POST /payments` is idempotency-key-gated (Phase 1's design) — a naive
fixed-request-body load tool (`hey`/`ab` with one static payload) would send
the *same* idempotency key on every request, and every request after the
first would just return the cached row without doing any real DB/queue/HTTP
work, making the "load" nearly free and never actually stressing
`payment-api`'s CPU. `k6`'s JS test script generates a unique body per
iteration natively, which is required for the load to be real load. `k6`'s
arrival-rate executor (target requests/second, independent of how long each
request takes) is also the right load shape here specifically because
chaos/latency injection (wave 3) will make response times vary — a
fixed-concurrency tool (N workers each looping request→wait→request) would
silently *reduce* throughput exactly when latency goes up, which is the
opposite of what a load test needs to demonstrate scaling under sustained
demand.

```js
// load/payment-load.js (illustrative)
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    ramp_load: {
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 200,
      stages: [
        { target: 5,  duration: '30s' },  // warm up
        { target: 80, duration: '2m'  },  // ramp — should cross 50% CPU
        { target: 80, duration: '3m'  },  // hold — watch HPA scale up
        { target: 0,  duration: '1m'  },  // ramp down — watch scale down
      ],
    },
  },
};

export default function () {
  const payload = JSON.stringify({
    amount: (Math.random() * 100).toFixed(2),
    idempotencyKey: `${__VU}-${__ITER}-${Date.now()}`,
  });
  const res = http.post('http://localhost:8080/payments', payload, {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { 'status is 202': (r) => r.status === 202 });
}
```

Run against `kubectl port-forward -n payguard svc/payment-api 8080:8080` in
one terminal, `k6 run load/payment-load.js` in another, `kubectl get hpa -n
payguard -w` and the Grafana scaling panels in a third/browser.

**Confirmed: `k6` runs entirely on the host (the Mac), not inside colima's
VM or the kind cluster.** `k6 run` is a plain macOS process hitting
`localhost:8080` through the `kubectl port-forward` tunnel — its
`preAllocatedVUs`/`maxVUs` (50/200) consume host CPU/memory for generating
and tracking requests, not anything inside colima's 4 CPU/6GiB budget. This
means the load generator itself is not a line item in Decision §10's
resource table, and there's no benefit to trimming `preAllocatedVUs`/
`maxVUs` for the sake of cluster-side headroom — the values above are sized
for the host's ability to sustain the target request rate, unrelated to the
colima-sizing question.

### 10. Colima resource budget — no resize; trimmed to fit 4 CPU/6GiB

**User decision: colima stays at its current 4 CPU/6GiB (no resize this
phase).** This section replaces an earlier draft that recommended resizing
to 6 CPU/10GiB — everything below is trimmed hard enough to target the
*existing* allocation, with real numbers, not just a restated intent to
"trim more."

**Step 1 — pre-Phase-7 baseline (currently live, unchanged):**

| Workload | cpu req | mem req | Replicas | Subtotal cpu / mem |
|---|---|---|---|---|
| payment-api | 100m | 256Mi | 1 | 100m / 256Mi |
| worker | 100m | 256Mi | 2 | 200m / 512Mi |
| postgres | 100m | 128Mi | 1 | 100m / 128Mi |
| redis | 50m | 64Mi | 1 | 50m / 64Mi |
| **Pre-Phase-7 total** | | | | **450m / 960Mi** |

**Step 2 — this phase's own steady-state floor increase.** Decision §7 bumps
`payment-api`'s `minReplicas` to 2 (from the currently-live 1); `worker`'s
`minReplicas` stays 2 (already live, no change). Net delta: **+1
`payment-api` pod = +100m / +256Mi**. New steady-state app floor: **550m /
1216Mi**.

**Step 3 — new components, trimmed (values reflect the Decision §4 sketch
above; `node-exporter` disabled, `kube-state-metrics` deliberately kept —
see the callout below):**

| Component | cpu req | mem req |
|---|---|---|
| mock-downstream (×1) | 100m | 256Mi |
| chaos-injector (×1, new this round) | 100m | 256Mi |
| OTel Collector (×1) | 50m | 96Mi |
| Tempo (single-binary) | 50m | 128Mi |
| metrics-server | 50m | 64Mi |
| Prometheus Operator | 50m | 64Mi |
| Prometheus server (retention cut to 3h) | 50m | 200Mi |
| Alertmanager | 25m | 32Mi |
| Grafana | 50m | 96Mi |
| ~~node-exporter~~ | **disabled** | **disabled** |
| kube-state-metrics | 50m | 128Mi |
| **New steady-state total** | **575m** | **1320Mi (~1.29Gi)** |

**Why `node-exporter` is cut but `kube-state-metrics` is not — checked
against Decision §5's actual panel list, not assumed:** every per-pod
CPU/memory panel in Decision §5 reads `container_cpu_usage_seconds_total`/
`container_memory_working_set_bytes` — **cAdvisor metrics scraped via
kubelet**, not node-exporter (which exposes *host/node*-level metrics like
`node_cpu_seconds_total`, none of which any panel in this design queries).
Disabling it costs zero panels. The two HPA-visibility panels
(`kube_horizontalpodautoscaler_status_current_replicas`/
`kube_deployment_status_replicas`), by contrast, have **no cAdvisor
equivalent** — they only exist via kube-state-metrics, and they're this
phase's single most novel dashboard requirement (the 2026-07-08 addition:
"the scaling event itself visible on the Grafana dashboards... not just
inferred from `kubectl get pods`"). Cutting kube-state-metrics to save
~50m/128Mi would silently break the one thing this phase's autoscaling
requirement is actually asking to see — not a trade worth making. (An
earlier draft of this doc flagged both as interchangeable "last resort"
candidates without checking this — corrected here.)

**Step 4 — Argo CD + kube-system overhead (estimated, not measured in this
repo).** Argo CD's plain install manifest sets no resource requests, so
there's no authoritative number to cite — Phase 6's notes only establish
that its ~6-7 components needed *some* meaningful share of the jump from
2 CPU/2GiB to 4 CPU/6GiB. Based on typical community-reported figures for
these components at idle (`application-controller`, `repo-server`,
`api-server`, `redis`, `applicationset-controller`,
`notifications-controller`), a reasonable estimate is **~300-500m cpu /
~600Mi-1GiB mem** actual usage; this doc uses the midpoint, **400m/800Mi**,
and flags the uncertainty explicitly rather than treating it as solid
(Open Questions). Kubernetes' own system daemons on the single kind node
(kubelet, containerd, kube-proxy, CoreDNS's 2 pods) are estimated at a
further **~250m cpu / ~350Mi mem**.

**Step 5 — steady-state total (before any HPA scale-up):**

| | cpu | mem |
|---|---|---|
| App floor (Step 1 + 2) | 550m | 1216Mi |
| New components (Step 3) | 575m | 1320Mi |
| Argo CD + kube-system (Step 4, estimated) | 650m | 1150Mi |
| **Steady-state total** | **1775m (~1.8 vCPU)** | **3686Mi (~3.6GiB)** |

Against colima's 4000m/6144Mi: **~2225m cpu / ~2458Mi mem (~2.4GiB) headroom**
before any load test starts.

**Step 6 — HPA peak burst** (both HPAs at `maxReplicas` simultaneously,
Decision §7's now-reduced ceilings): `payment-api` `minReplicas: 2` →
`maxReplicas: 4` = +2 pods; `worker` `minReplicas: 2` → `maxReplicas: 3` =
+1 pod. Burst delta: **+300m cpu / +768Mi mem** (requests tier).

**Step 7 — peak total, requests tier:**

| | cpu | mem | % of 4 CPU/6GiB |
|---|---|---|---|
| Steady-state (Step 5) | 1775m | 3686Mi | — |
| + HPA burst (Step 6) | +300m | +768Mi | — |
| **Peak total** | **2075m** | **4454Mi (~4.35GiB)** | **~52% cpu / ~72% mem** |

**~1.65GiB (~28%) memory headroom remains at peak**, and cpu stays under
55% throughout — a real, calculated margin, not a hand-wave, but
categorically **tighter than the resize plan would have given** (that
version sat at ~52% cpu / ~69% mem *before* even applying these harder
trims). Two honest caveats, stated plainly rather than glossed over:

1. The Argo CD estimate (Step 4) is the single largest source of
   uncertainty in this whole budget — it's a community-typical estimate, not
   a measurement of *this* cluster's Argo CD install. If its real footprint
   sits at the high end of plausible ranges (or higher), the ~1.65GiB
   margin shrinks accordingly.
2. This is a `requests`-tier calculation, which governs whether the
   scheduler *admits* a pod, not what it might actually use. If every JVM
   service's OTel-agent overhead (Decision §3: ~50-100MiB per pod) plus
   Prometheus/Grafana's own query-time bursts all land simultaneously at
   their `limits` rather than their `requests`, the real ceiling is closer
   than the requests-tier table suggests — genuinely unlikely to happen
   all at once during a manually-run demo, but not impossible.

**Bottom line: this fits at 4 CPU/6GiB with the trims above applied — a
real but tight margin, not a comfortable one.** If `Pending` pods or
OOMKills appear anyway once this phase is actually built, the levers, in
order, are: (a) reduce `payment-api`'s `maxReplicas` further (4 → 3), (b)
drop Prometheus retention further (3h → 1h), (c) reduce `worker`'s
`maxReplicas` to 2 (i.e. no worker autoscaling headroom at all, accepting
a weaker secondary demo per Decision §7's own note that it isn't the
primary one anyway). Note that tuning the k6 script's `preAllocatedVUs`/
`maxVUs` is **not** on this list — per Decision §9, `k6` runs on the host,
outside colima's budget entirely, so it has no bearing on cluster-side
memory pressure. Re-litigating the colima resize is explicitly **not** the
first lever to reach for if pressure appears — see Open Questions for the
honest flag that this margin could still prove insufficient in practice.

## Alternatives Considered & Tradeoffs

| Option | Pros | Cons | Why not chosen |
|---|---|---|---|
| gRPC instead of HTTP/REST for worker → chaos-injector → mock-downstream | Lower overhead; "more cloud-native"; strongly-typed contract via `.proto` | Adds protobuf codegen tooling to three services' Maven builds for two simple hops; OTel agent's HTTP-client auto-instrumentation is more mature/zero-config than its gRPC path in some Spring Boot setups | HTTP/REST needs no new build tooling and the OTel agent instruments Spring's `RestClient` for free |
| In-process lib inside `mock-downstream` instead of standalone-proxy chaos-injector | Zero new Dockerfile/manifest/CI/instrumentation surface; simplest possible implementation | Chaos and "the real answer" happen one function call apart in the same JVM — can't simulate an actual delayed/dropped network hop, only an application-level decision that merely looks similar from the outside; this doc's own original recommendation | User chose the standalone-proxy option for more realistic network-level chaos simulation and a cleaner separation of concerns (`mock-downstream` stays fault-unaware) — see full reasoning and 3-row table under Decision §2 |
| Sidecar container (Envoy/nginx-style fault injection) instead of standalone-proxy chaos-injector | Closer to real service-mesh fault injection, a transferable industry pattern | Requires learning a proxy's own fault-injection config language on top of everything else this phase adds; `CLAUDE.md` explicitly rules out a service mesh for this project | Same category of tooling `CLAUDE.md` already excludes; a plain Spring Boot service matching every other service's stack teaches the target lesson without a second config language |
| Manual OTel SDK instrumentation instead of the Java agent | Fine-grained control over span names/attributes; no agent startup overhead | Requires code changes in all four services; more surface area to get wrong in the largest phase to date | Auto-instrumentation covers everything this phase's verification steps need with zero app code changes |
| Route metrics through the OTel Collector too (OTLP metrics + Collector's Prometheus exporter) instead of direct Micrometer/actuator scraping | One unified telemetry pipeline for both traces and metrics, arguably more "correct" long-term architecture | More config (Collector metrics pipeline, a Prometheus exporter component) to achieve an identical end state Micrometer already gives for free; Spring Boot's Prometheus integration is the standard, zero-friction path | Direct scraping is simpler and this project has no second metrics consumer that would benefit from Collector-mediated metrics |
| Jaeger instead of Tempo as the trace backend | Equally capable, well-known, battle-tested; simple all-in-one deployment mode exists too | Ships its own separate UI — a second pane of glass alongside Grafana, which the "visualize in Grafana" objective argues against | Tempo's native Grafana data source keeps one UI for the whole observability story |
| No trace backend at all — Collector's logging exporter only, traces visible via `kubectl logs` | Zero new stateful service; smallest possible footprint | Doesn't satisfy "visualize... in Grafana" (Learning Objective #5); makes the chaos-injector verification step ("confirm it's visible in traces") much weaker — grepping log lines instead of a trace waterfall | Tempo's footprint is small enough not to be worth this tradeoff — see Open Questions for confirming this addition is wanted |
| Sidecar-per-pod OTel Collector instead of one shared Deployment | No shared bottleneck; per-pod isolation | 3× the resource requests for 3× the config to maintain; no per-node enrichment need on a single-node cluster that would justify it | One shared Collector is simpler and cheaper on an already resource-constrained laptop cluster |
| Hand-rolled Prometheus + Alertmanager + Grafana (no Operator, no chart) instead of `kube-prometheus-stack` | Full contents of the manifest under this project's own control, in keeping with the general "hand-write it" habit | Reimplements a nontrivial, well-solved controller (scrape-config-from-CRDs) for zero PayGuard-specific learning value; the *real* skill (install + configure the standard chart, author your own CRDs against it) is what this design already teaches | Explicitly reasoned through in Decision §4 — this is a case where the standard chart is the "don't over-engineer" choice, not its opposite |
| Grafana's own unified alerting instead of Alertmanager | One fewer component conceptually, since Grafana is already in the stack | Alertmanager ships in the same chart at zero extra install cost; running both would be two competing alert evaluators over the same data | Alertmanager is already present; using it is strictly less new surface than also configuring Grafana alerting |
| `worker` as the primary/only HPA demo target instead of `payment-api` | Directly exercises the service that talks to the chaos-injected downstream | Worker's workload is mostly I/O-wait, not CPU-bound — a CPU-utilization HPA target is unreliable to trigger convincingly via load; would need artificial CPU-bound work with no teaching value | `payment-api`'s CPU load scales directly and predictably with HTTP request volume |
| Disable `selfHeal` on the `payguard` Application instead of `ignoreDifferences` | Simpler to write (delete two lines instead of adding a scoped exception) | Throws away Phase 6's entire drift-protection story for every field, not just `replicas` — a `kubectl edit` to the image tag or an env var would also silently stick around, undetected | `ignoreDifferences` is the standard, precisely-scoped fix; global `selfHeal: false` is a sledgehammer for a scalpel problem |
| Omit `spec.replicas` from the Deployment manifest entirely, letting the HPA fully own it with no Git-declared value | Also avoids the conflict, no `ignoreDifferences` block needed | Less discoverable — a reader of the Deployment YAML has no clue HPA governs replicas unless they already know to look for an HPA object; the exception isn't co-located with the GitOps policy granting it | `ignoreDifferences` documents the exception exactly where the GitOps policy itself lives, in `application.yaml` |
| `hey`/`vegeta`/`ab` instead of `k6` | Single static binary, faster to reach for, no scripting needed for a trivial fixed-payload test | Fixed request body collides with `POST /payments`'s idempotency-key gating — repeated identical bodies collapse into cache hits, not real load; fixed-concurrency executors reduce throughput exactly when chaos-induced latency rises | `k6`'s scripted, unique-per-request payloads and arrival-rate executor are specifically required by this app's idempotency design, not just a nice-to-have |
| Resize colima to 6 CPU/10GiB instead of trimming further | Comfortable margin (~2.3vCPU/2.5GiB headroom at HPA peak per an earlier draft of this budget); well within the host's 10-core/16GB capacity; matches Phase 6's own "size proactively" lesson | Rejected by the user — no change to Phase 6's already-established sizing footprint | User confirmed staying at 4 CPU/6GiB; Decision §10's harder trims (node-exporter disabled, reduced HPA ceilings, shorter Prometheus retention) fit this budget with real, if tighter, margin |

## Contracts

**New services / files this design specifies** (created by the implementer,
none created by this doc): `services/mock-downstream/` and
`services/chaos-injector/` (each: Dockerfile, `pom.xml`, `application.yml`,
`.dockerignore`, source), `load/payment-load.js`,
`docs/runbooks/payment-failure-rate.md`, plus additions to `infra/k8s/`
(numbered continuing Phase 5's convention), `infra/helm/payguard/`,
`infra/argocd/application.yaml`, and `.github/workflows/ci.yml`.

**`mock-downstream` HTTP API** (unchanged by who calls it — no chaos
awareness of its own):
| Method | Path | Request body | Response |
|---|---|---|---|
| `POST` | `/v1/process` | `{"paymentId": "<uuid>", "amount": <number>}` | Always `200 {"paymentId": "<uuid>", "status": "PROCESSED"}` |

**`chaos-injector` HTTP API** (the proxy `worker` actually calls; forwards
to or short-circuits before `mock-downstream`):
| Method | Path | Request body | Success | Failure modes |
|---|---|---|---|---|
| `POST` | `/v1/process` | `{"paymentId": "<uuid>", "amount": <number>}` | `200` — passthrough of `mock-downstream`'s real response (`NONE`/`LATENCY` modes) | `503` (`ERROR_5XX` short-circuit), `400` (`ERROR_4XX` short-circuit), timeout (`DROP` mode, or `LATENCY` exceeding worker's read timeout) — `mock-downstream` is never called on any short-circuited branch |
| `GET` | `/chaos/config` | — | `200 {"mode": "...", "latencyMs": N, "probabilityPct": N}` | — |
| `PUT` | `/chaos/config` | `{"mode": "NONE\|LATENCY\|ERROR_5XX\|ERROR_4XX\|DROP", "latencyMs": N, "probabilityPct": N}` | `200`, echoes new state | `400` on an unrecognized `mode` |

**Worker's new/changed classes**: `DownstreamProcessor` rewritten to call
`chaos-injector` via `RestClient` instead of simulating in-process;
`TransientDownstreamException` retargeted at real HTTP/timeout failures from
`chaos-injector` (unchanged retry annotation: `maxAttempts=3,
backoff=200ms×2`); new `PermanentDownstreamException` (not retried,
immediate `FAILED`) for `400` responses from `chaos-injector`.

**`chaos-injector`'s own new classes**: mirrors `worker`'s outbound-client
shape — its own `RestClient` calling `mock-downstream` on the passthrough
path, plus an in-memory `ChaosConfig` (mode + `latencyMs` + `probabilityPct`)
backing `GET`/`PUT /chaos/config`, consulted by the `/v1/process` handler
before deciding to forward, delay-then-forward, or short-circuit.

**Worker → chaos-injector client config**:
| Setting | Value |
|---|---|
| `CHAOS_INJECTOR_URL` (env, from ConfigMap, consumed by `worker`) | `http://chaos-injector:8080` |
| Connect timeout | 1s |
| Read timeout | 3s |
| `DROP` chaos sleep duration | 5s (must exceed the 3s read timeout to reliably produce a client-side timeout) — executed by `chaos-injector`, not `mock-downstream` |

**`chaos-injector` → `mock-downstream` client config** (new — `chaos-injector`'s
own outbound call on the passthrough/`LATENCY` path):
| Setting | Value |
|---|---|
| `MOCK_DOWNSTREAM_URL` (env, from ConfigMap, consumed by `chaos-injector` now, not `worker`) | `http://mock-downstream:8080` |
| Connect/read timeout | Same 1s/3s shape as worker's client, so a genuinely slow/unhealthy `mock-downstream` surfaces the same way a `DROP`/`LATENCY` chaos mode would |

**New Micrometer metric**: `payments_processed_total{status="COMPLETED"\|"FAILED"}`
— a `Counter`, incremented in `PaymentProcessingService` at the same point
`status` is set, once for the terminal outcome per payment.

**HTTP/management ports**:
| Service | App port | Management port | k8s Service? | Called by |
|---|---|---|---|---|
| `payment-api` | 8080 (unchanged) | 8081 (new — `/actuator/prometheus`, `/actuator/health`) | Yes (8080 only; PodMonitor targets 8081 on the pod directly, no Service change needed) | External clients / `curl` / k6 |
| `worker` | none (unchanged — no business HTTP surface) | 8081 (new — this is worker's *only* port, serving both `/actuator/prometheus`/`/actuator/health` since there's no competing business port to keep separate from) | No (unchanged — PodMonitor scrapes pod IPs, doesn't need a Service) | Nothing (Redis Streams consumer, no inbound HTTP business traffic) |
| `chaos-injector` | 8080 (new — `/v1/process`, `/chaos/config`) | 8081 (new — `/actuator/prometheus`, `/actuator/health`) | Yes (8080 only) | `worker` (business traffic), operator (`curl` to `/chaos/config`) |
| `mock-downstream` | 8080 (unchanged shape — `/v1/process` only, no chaos config) | 8081 (new — `/actuator/prometheus`, `/actuator/health`) | Yes (8080 only) | `chaos-injector` only — `worker` never calls it directly |

**New pom.xml dependencies**: `spring-boot-starter-actuator` +
`micrometer-registry-prometheus` (all four services); `spring-boot-starter-web`
(worker only — it has none today, needed to serve the new management port;
`mock-downstream` and `chaos-injector` both need `spring-boot-starter-web`
too, but as new services they simply include it from the start rather than
"gaining" it).

**OTel Java agent** (Dockerfile addition, all four services' runtime
stage):
```dockerfile
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.10.0/opentelemetry-javaagent.jar /app/otel-agent.jar
ENTRYPOINT ["java", "-javaagent:/app/otel-agent.jar", "-jar", "app.jar"]
```
(`ADD <url>` is a native Dockerfile feature for fetching a remote file at
build time — chosen over `RUN curl`/`RUN wget` since the Alpine runtime
base image has no `curl` and only BusyBox `wget`; `ADD` needs neither,
since it's resolved by the Docker builder itself, not a shell command
inside the image. Implementer: confirm the latest stable agent release tag
at build time.)

**OTel env vars** (all four services):
| Var | Value | Notes |
|---|---|---|
| `OTEL_SERVICE_NAME` | `payment-api` / `worker` / `chaos-injector` / `mock-downstream` | Literal per-Deployment, not in the shared ConfigMap (structural identity, not a deployment-time tunable — same rule Phase 5 used for ports/env-var names) |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://otel-collector:4317` | From ConfigMap (non-secret DNS name+port, same pattern as `REDIS_HOST`) |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `grpc` | From ConfigMap |
| `OTEL_TRACES_EXPORTER` | `otlp` | From ConfigMap |
| `OTEL_METRICS_EXPORTER` | `none` | From ConfigMap — explicitly disables the agent's own metrics export since Micrometer/Prometheus is the chosen metrics path (Decision §3) |
| `OTEL_LOGS_EXPORTER` | `none` | From ConfigMap — logs stay plain stdout |

**OTel Collector pipeline** (`infra/k8s/`, `payguard` namespace):
receivers: `otlp` (grpc :4317, http :4318). processors: `batch`. exporters:
`otlp/tempo` → `http://tempo.monitoring.svc.cluster.local:4317`.

**Namespaces**: `payguard` (app + Collector, Argo CD-tracked, unchanged),
`argocd` (unchanged), `monitoring` (new — `kube-prometheus-stack` + Tempo,
imperative Helm installs, not Argo CD-tracked).

**PodMonitor** (`infra/k8s/`, one per service — now four:
`payment-api`, `worker`, `chaos-injector`, `mock-downstream` — `payguard`
namespace, scrapes pod port `8081` path `/actuator/prometheus`).

**Alert rule**: exact `PrometheusRule` YAML in Decision §6.
**HPA specs**: exact YAML in Decision §7 (`payment-api` `maxReplicas: 4`,
`worker` `maxReplicas: 3`).
**Argo CD `ignoreDifferences`**: exact YAML in Decision §8, added to the
existing `infra/argocd/application.yaml`.

**CI matrix**: `.github/workflows/ci.yml`'s `service:` matrix array gains
**two** entries, `mock-downstream` and `chaos-injector`
(`service: [payment-api, worker, mock-downstream, chaos-injector]`) — per
Phase 4's own design doc, which already anticipated this exact moment ("a
matrix scales cleanly if `mock-downstream` arrives in a later phase — add
one array element, not a whole new file," `PHASE_4_DESIGN.md` line 172);
`chaos-injector` gets the identical treatment now that it's confirmed as
its own deployable, not the "no separate CI entry" exception an earlier
draft of this doc assumed.

**Colima**: **no change this phase** — stays at 4 CPU/6GiB. The trimmed
`kube-prometheus-stack` values (Decision §4) and reduced HPA ceilings
(Decision §7) are load-bearing for this to fit, not optional polish — see
Decision §10's full budget.

**Load tool**: `brew install k6`; script at `load/payment-load.js`, exact
content in Decision §9.

## Open Questions

- [ ] **Tempo is an addition beyond `CLAUDE.md`'s currently-stated tech
      stack** (which names only "Prometheus + Grafana + OpenTelemetry
      Collector," no trace backend). This doc recommends adding it because
      traces need somewhere queryable to satisfy "visualize... in Grafana"
      — confirm this addition, or say if a logging-exporter-only fallback
      (no queryable trace store, traces visible only via `kubectl logs` on
      the Collector) is preferred instead, accepting a weaker chaos-visibility
      story.
- [ ] **`kube-prometheus-stack` as a third-party Helm chart** is a bigger
      infra footprint than anything installed via `helm install` so far
      except Argo CD itself — confirm the reasoning in Decision §4 (treating
      it like Argo CD's own bootstrap, not like Phase 5's app chart) is the
      right call, or say if a more minimal/hand-assembled Prometheus setup
      is preferred despite the added effort and lower transferable-skill
      value argued above.
- [x] ~~This doc revises `PHASE_7_TASKS.md`'s implicit "chaos-injector is
      its own deployable" assumption...~~ **Resolved**: the user confirmed
      `chaos-injector` as a standalone proxy service — `PHASE_7_TASKS.md`'s
      original phrasing was right after all; this doc's first-draft
      in-process-lib recommendation was not adopted. See Decision §2.
- [x] ~~Colima resize to 6 CPU/10GiB...~~ **Resolved**: the user declined
      the resize. Colima stays at 4 CPU/6GiB; Decision §10 gives the
      trimmed budget this requires.
- [ ] **New, replacing the resize question**: Decision §10's budget shows a
      real but tight margin (~52% cpu / ~72% mem at HPA peak, ~1.65GiB
      headroom) built on an *estimated*, not measured, Argo CD footprint —
      the single largest source of uncertainty in the whole calculation. If
      `Pending` pods or OOMKills appear once this phase is actually built
      despite the trims already applied (node-exporter disabled, reduced
      HPA ceilings, 3h Prometheus retention), the fallback levers are listed
      at the end of Decision §10 (reduce `maxReplicas` further, shorten
      retention further) — but if *those* also prove insufficient, the
      honest fallback is revisiting the colima resize question after all.
      Flagging this now, before implementation, rather than letting it
      surface as a surprise the way Phase 6's under-sized colima did.
- [ ] **Whether to also GitOps-manage the observability platform itself**
      (a second Argo CD `Application` tracking a future `infra/observability/`
      Kustomize path for `kube-prometheus-stack`'s values + Tempo, instead
      of the one-time imperative `helm install` this doc recommends) — not
      designed here, explicitly deferred as future work; flagging in case
      the preference is to do this now instead.
- [x] ~~`PermanentDownstreamException`/non-retryable-4xx handling is new
      scope beyond Phase 1's original binary model...~~ **Resolved**: the
      user confirmed `ERROR_4XX` + `PermanentDownstreamException` in scope
      for this phase, as recommended. Its home moved from `mock-downstream`
      to `chaos-injector` as part of the standalone-proxy decision (Decision
      §1/§2) — the exception/retry semantics on `worker`'s side are
      otherwise unchanged from the original recommendation.

## Out of Scope

- A general-purpose chaos-engineering platform — no scheduler, no
  steady-state hypothesis engine, no automatic rollback, no blast-radius
  controls. One manually-toggled fault switch on one service, per the
  Concept Primer's explicit framing.
- Log aggregation (Loki or similar) and an OTel logs pipeline — logs stay
  plain container stdout, viewable via `kubectl logs`, same as every prior
  phase.
- Service mesh (Istio/Linkerd) — `CLAUDE.md` already rules this out
  explicitly; the sidecar-proxy chaos-injection alternative in Decision §2
  was rejected partly on this basis.
- A GitOps-managed observability platform (a second Argo CD `Application`
  for `kube-prometheus-stack`/Tempo) — imperative install only this phase,
  see Open Questions.
- Long-term metrics retention, remote-write to an external TSDB, or any
  multi-cluster/multi-environment observability federation — 6h Prometheus
  retention is intentionally short for a laptop demo, not a production
  setup.
- Alertmanager notification receivers (Slack/PagerDuty/email) — no
  operational audience exists for a solo learning project, consistent with
  Phase 6's identical call on Argo CD Notifications.
- Progressive delivery / canary deployment via HPA + Argo Rollouts —
  already out of scope per Phase 6, unrelated to this phase's autoscaling
  work (which is reactive load-based scaling, not deployment strategy).
- Multi-architecture image builds for the new `mock-downstream`/
  `chaos-injector`/Collector images — same reasoning as every prior phase,
  this runs only on the owner's own machine.
- A container registry for `mock-downstream` or `chaos-injector` — still
  `kind load docker-image`, no registry, consistent with every prior phase.
- Vertical Pod Autoscaling (VPA) — this phase is explicitly about
  horizontal scaling (`PROJECT_PLAN.md`'s stated ask); VPA solves a
  different problem (right-sizing requests/limits) not asked for here.
- Terraform-managed local infra (Phase 8).
