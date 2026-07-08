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
- [ ] `mock-downstream` service — simulates the payment processor
      `worker`'s `DownstreamProcessor` currently stubs in-process (Phase 1
      design); design doc decides whether `worker` starts calling this over
      the network instead, and what changes that implies
- [ ] `chaos-injector` — config-driven fault injection (latency, drops,
      5xxs) per `PROJECT_PLAN.md`'s architecture diagram
- [ ] Both get Dockerfiles + k8s manifests (+ Helm chart entries) consistent
      with Phase 3/5's existing patterns

## Observability
- [ ] OpenTelemetry instrumentation across `payment-api`, `worker`, and the
      new `mock-downstream` — traces at minimum; metrics if the design doc
      recommends it over/alongside Prometheus's own scraping
- [ ] OpenTelemetry Collector, deployed alongside the app
- [ ] Prometheus deployed into the cluster, scraping all services
- [ ] Grafana deployed, with dashboards covering: per-service resource
      stats (CPU/memory — building on Phase 5's requests/limits), request
      and transaction volume, and (new) live replica count / scaling events
- [ ] One alert rule (e.g. error rate > 5% for 2 minutes) wired to fire
      somewhere observable (Grafana alerting, Alertmanager — design doc
      decides)
- [ ] One runbook doc describing what to do when that alert fires

## Autoscaling
- [ ] `metrics-server` installed into the kind cluster (not present by
      default — required for any CPU/memory-based HPA to function at all)
- [ ] `HorizontalPodAutoscaler` for `payment-api` (and/or `worker` — design
      doc decides which, or both) with real min/max replicas and a target
      utilization
- [ ] **Resolve the Argo CD conflict**: `infra/k8s/*-deployment.yaml`
      declares a fixed `replicas` count, and Phase 6's Application has
      `selfHeal: true` — without a fix, Argo CD will revert every HPA-driven
      scale-up right back down, the same behavior proven live in Phase 6's
      own drift-correction demo. Design doc must specify the actual
      mechanism (Argo CD's `spec.ignoreDifferences` on the replicas field is
      the standard answer — confirm or propose an alternative) and update
      `infra/argocd/application.yaml` and/or the HPA manifest accordingly.
- [ ] A load-generation tool/script to actually drive enough volume against
      `payment-api` to trigger a scale-up, and let it settle back down once
      load stops

## Manual verification
- [ ] Chaos: trigger a configured fault via the chaos injector and confirm
      it's visible in traces/metrics/logs, and that the system's existing
      retry/resilience behavior (Phase 1) handles it as designed
- [ ] Observability: Grafana dashboards show live, real data for all the
      categories above; the alert rule actually fires under a forced
      condition and the runbook's steps are accurate
- [ ] Autoscaling: run the load-generation tool, watch replica count rise
      on the Grafana dashboard (and `kubectl get hpa -w`) as load increases,
      confirm the Argo CD Application stays `Synced`/`Healthy` throughout
      (not fighting the HPA), then stop the load and watch it scale back
      down

## Wrap-up
- [ ] Write `docs/phase-notes/PHASE_7_NOTES.md`
- [ ] Write `docs/demos/PHASE_7_DEMO.md`
- [ ] Update root `README.md` and `CLAUDE.md`'s Current Phase line
- [ ] Commit with message `feat: phase 7 — chaos, observability, autoscaling`
