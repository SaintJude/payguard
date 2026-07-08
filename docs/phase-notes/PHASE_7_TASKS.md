# Phase 7 Tasks — Chaos + Observability (+ Autoscaling)

Goal, per `PROJECT_PLAN.md`'s Phase 7 section and its two logged
requirements (2026-07-07 observability, 2026-07-08 autoscaling): wire in
real chaos injection, instrument all services with OpenTelemetry, stand up
Prometheus + Grafana with dashboards covering system health, resource
stats, and transaction volume, build one alert rule and its runbook, add a
`HorizontalPodAutoscaler` for `payment-api`/`worker`, and give the system a
load-generation tool so scaling up and down under volume is something you
can actually trigger and *watch happen* on the Grafana dashboards — not
just infer from `kubectl get pods`.

This is the biggest phase yet by a wide margin (net-new services, a metrics
pipeline, an alerting stack, and a real architectural conflict between
Argo CD's `selfHeal` and HPA-driven scaling that Phase 6 didn't need to
solve). The architect should propose how to sequence/split implementation
— this list is the full scope, not necessarily one implementer dispatch.

## Chaos injection
- [x] `mock-downstream` service — a boring, stateless, always-succeeds
      simulated processor; the Phase 1 in-process stub is fully removed,
      `worker` now makes a real HTTP call (through `chaos-injector`, see
      below, never directly)
- [x] `chaos-injector` — a standalone proxy service (not an in-process lib —
      revised from the architect's first draft per explicit user direction)
      sitting between `worker` and `mock-downstream`, with config-driven
      fault injection (`NONE`/`LATENCY`/`ERROR_5XX`/`ERROR_4XX`/`DROP`) via
      a live `GET`/`PUT /chaos/config` REST surface
- [x] Both get Dockerfiles + k8s manifests + Helm chart entries + CI matrix
      entries, consistent with Phase 3/4/5's existing patterns

## Observability
- [x] OpenTelemetry instrumentation (Java agent, auto-instrumentation) across
      all four services: `payment-api`, `worker`, `chaos-injector`,
      `mock-downstream` — traces only, exported to a shared Collector
- [x] OpenTelemetry Collector, deployed alongside the app in `payguard`,
      exporting to Grafana Tempo
- [x] Prometheus (`kube-prometheus-stack`) deployed into a new `monitoring`
      namespace, scraping all four services via `PodMonitor`s
- [x] Grafana deployed, with the "PayGuard Overview" dashboard: 11 panels
      across Resources (per-pod CPU/memory), Traffic (request rate, p95
      latency, transaction volume, failure rate), Chaos (chaos-injector vs.
      mock-downstream request rates), and Scaling (HPA current/desired
      replicas, actual replica count, CPU utilization vs. target)
- [x] One alert rule (`PaymentFailureRateHigh`, failure ratio > 5% for 2
      minutes) wired to Alertmanager — verified firing for real (see Manual
      verification below)
- [x] Runbook at `docs/runbooks/payment-failure-rate.md`

## Autoscaling
- [x] `metrics-server` installed (imperative, with the kind-specific
      `--kubelet-insecure-tls` patch) — `kubectl top` confirmed working
- [x] `HorizontalPodAutoscaler` for both `payment-api` (min 2/max 4, 50%
      CPU) and `worker` (min 2/max 3, 60% CPU)
- [x] **Resolved the Argo CD conflict** via `spec.ignoreDifferences` on
      `/spec/replicas`, scoped per-Deployment, added to
      `infra/argocd/application.yaml` — see Manual verification for the
      final live proof, which happens after this PR merges (Argo CD only
      syncs from `main`; auto-sync was paused for the duration of this
      phase's build-out to allow direct testing, see `PHASE_7_NOTES.md`)
- [x] `k6` load-generation script at `load/payment-load.js` — verified: a
      real ~6.5-minute run scaled `payment-api` 2→4 and `worker` 2→3, then
      both settled back to `minReplicas: 2` after load stopped

## Manual verification
- [x] Chaos: all five fault modes verified both in isolation and through
      worker's full retry chain — `ERROR_5XX` resolves to `FAILED` after 3
      retries (~0.73s), `ERROR_4XX` resolves to `FAILED` immediately with no
      retry (~0.24s, resolving Phase 1's deferred permanent-vs-transient
      question), visible in Tempo traces and the `payments_processed_total`
      metric
- [x] Observability: all 11 dashboard panels confirmed returning real,
      non-empty data (checked directly against Prometheus's query API); the
      alert rule confirmed firing for real under forced chaos (state
      transitioned `inactive` → `pending` → `firing` after a sustained
      2-minute window, reached Alertmanager, resolved after chaos was
      turned off) — runbook's diagnostic commands verified accurate
- [x] Autoscaling: the `k6` load test confirmed both HPAs scale up under
      real CPU load and back down to `minReplicas` once load stops.
      **Post-merge**: re-enabled Argo CD's automated sync from `main`
      (now containing the `ignoreDifferences` fix) — reached `Synced`/
      `Healthy` immediately, no reversion. Manually scaled `payment-api`
      to 3 replicas with `selfHeal: true` active; sync status stayed
      `Synced` continuously throughout (never even briefly flipped to
      `OutOfSync`, unlike Phase 6's own demo of the *unfixed* behavior) —
      confirming Argo CD no longer treats `spec.replicas` as drift at all.
      The eventual scale-down back to 2 was independently confirmed via
      `kubectl describe hpa` as the HPA's own `SuccessfulRescale` event
      ("All metrics below target"), not an Argo CD revert.

## Wrap-up
- [x] Write `docs/phase-notes/PHASE_7_NOTES.md`
- [x] Write `docs/demos/PHASE_7_DEMO.md`
- [x] Update root `README.md` and `CLAUDE.md`'s Current Phase line
- [x] Commit with message `feat: phase 7 — chaos, observability, autoscaling`
