# Phase 5 Tasks — Local Kubernetes

Goal: the same four-service system from Phase 3 (`payment-api`, `worker`,
Postgres, Redis) running on a local `kind` cluster instead of Docker
Compose, first as plain Kubernetes manifests, then re-packaged as a Helm
chart. Per `PROJECT_PLAN.md`'s target repo structure, manifests live under
`infra/k8s/` and the chart under `infra/helm/`.

## Setup
- [x] `kind create cluster` — a named local cluster (`payguard`, single-node)
- [x] Get the two locally-built images (`payment-api`, `worker`) into the
      kind cluster via `kind load docker-image`
- [x] Kubernetes manifests under `infra/k8s/` translating each
      `docker-compose.yml` service into a `Deployment` + `Service`:
      `payment-api`, `worker`, `postgres`, `redis` (plus a bare
      `kustomization.yaml` so `kubectl apply -k infra/k8s/` works)
- [x] `ConfigMap`/`Secret` translating Phase 3's compose `environment:`
      blocks (DB/Redis connection info, Postgres credentials)
- [x] A persistence mechanism for Postgres (`PersistentVolumeClaim`) so data
      survives pod restarts, mirroring Phase 3's named volume
- [x] Startup ordering equivalent to Compose's `depends_on`/healthchecks —
      readiness/liveness probes + Spring's own connection-retry behavior, no
      `initContainer` (see design doc's Decision)

## Manual verification
- [x] `kubectl apply -k infra/k8s/` brings up all four workloads and they
      reach `Ready`
- [x] Happy path from the Phase 3 demo works end-to-end against the kind
      cluster — submitted via `kubectl port-forward`, resolved
      `PENDING` → `COMPLETED`
- [x] Delete the `worker` pod and confirm Kubernetes restarts it
      automatically — confirmed live: a brand-new pod
      (`worker-...kw5t6`) replaced the deleted one
      (`worker-...khpm6`) within ~1 second, no `kubectl apply`/`create` run
      again. Explicit contrast with Phase 3's `restart: no`.
- [x] Confirm Postgres data survives a pod restart (not just a `kubectl
      apply` re-run) via the `PersistentVolumeClaim` — confirmed live:
      deleted the postgres pod, a new one (`postgres-...t6cqq`) came up, and
      the same payment row (same count, same id, same status) was still
      there. Submitted and resolved a fresh payment afterward to confirm the
      system is fully functional post-restart, not just that old data
      survived.

## Helm conversion
- [x] `infra/helm/payguard/` chart templating the manifests above, with a
      values file exposing image tags, replica counts, resource limits,
      Postgres persistence size, and Postgres credentials
- [x] `helm install` reproduces the same working system as the raw-manifest
      `kubectl apply -k` did — verified via an independent end-to-end
      payment test after installation
- [x] Confirm `helm uninstall` cleanly tears down everything the chart
      created (no orphaned resources) — confirmed, including the PVC
      (deleted, not orphaned — see `PHASE_5_NOTES.md` for why that's correct
      given this chart owns its own Namespace)

## Wrap-up
- [x] Write `docs/phase-notes/PHASE_5_NOTES.md`
- [x] Write `docs/demos/PHASE_5_DEMO.md`
- [x] Update root `README.md` and `CLAUDE.md`'s Current Phase line
- [x] Commit with message `feat: phase 5 — local kubernetes`
