# Phase 5 Notes — Local Kubernetes

## What was built

- A single-node `kind` cluster named `payguard`.
- `infra/k8s/`: raw manifests for all four workloads (`payment-api`,
  `worker`, `postgres`, `redis`) in a dedicated `payguard` namespace —
  Deployments, Services, a ConfigMap/Secret split, a `PersistentVolumeClaim`
  for Postgres, readiness/liveness probes translating Phase 3's healthcheck
  commands, and a bare `kustomization.yaml` so `kubectl apply -k infra/k8s/`
  (already documented in `CLAUDE.md`) works verbatim.
- `infra/helm/payguard/`: the same system re-packaged as a Helm chart,
  parameterizing only what plausibly varies between deployments (image
  tags, replica counts, resource limits, Postgres persistence size and
  credentials) — everything structural (ports, env var names,
  `imagePullPolicy: Never`, namespace name) stays a literal in the
  templates.
- Full rationale, including two decisions confirmed with the user before
  implementation (`replicaCount: 1` for now, add a bare `kustomization.yaml`,
  keep floating-major image tags for Postgres/Redis), in
  `docs/architecture/PHASE_5_DESIGN.md`.

## Surprises / gotchas

- **The image-visibility gotcha was real, not just theoretical.** Locally
  built images (`payguard-payment-api:latest`, `payguard-worker:latest`)
  are invisible to kind's node until explicitly copied in via `kind load
  docker-image` — this isn't a one-time setup step, it has to be re-run
  after every image rebuild, since the tag doesn't change and Kubernetes has
  no way to know new bytes exist behind it. `imagePullPolicy: Never` was the
  right call here: it turns "forgot to reload" into an immediate, visible
  `ErrImageNeverPull` instead of a confusing silent failure.
- **payment-api and worker both restarted once on first startup — expected,
  not a bug.** Per the design doc's Concept Primer, Kubernetes has no
  `depends_on`; Spring's JDBC/Redis clients are supposed to retry
  transparently, but in practice HikariCP/Flyway failed fast on the very
  first connection attempt (before Postgres's own readiness probe had even
  passed) rather than retrying in-process, so the container exited and the
  Deployment controller's own pod-restart became the actual mechanism that
  made ordering self-heal — one layer up from where the design doc expected
  it to happen. The end result (eventually consistent, no data loss, no
  human intervention) matches the design's intent; the retry mechanism
  itself sits at the Deployment level instead of purely inside the JVM. This
  is worth knowing before assuming a "1 restart" count in `kubectl get
  pods` output is something wrong with the manifests.
- **Deleting a pod is instructive in a way `kubectl apply` re-runs are not.**
  Watching `worker-...khpm6` get replaced by `worker-...kw5t6` within about a
  second, or Postgres come back on a new pod name with the same data intact,
  makes the Deployment-controller/PVC concepts concrete in a way reading the
  design doc's Concept Primer alone doesn't. Worth keeping as a standard
  verification step for any future phase that touches Kubernetes.
- **Helm's `helm uninstall` deletes the Postgres PVC — because this chart
  owns its own Namespace.** This surprised the implementer enough to
  investigate rather than assume: with `helm.sh/resource-policy: keep` not
  set anywhere and no `volumeClaimTemplates`-based StatefulSet pattern in
  use, every object the chart created — including the namespace itself —
  gets torn down together, and namespace deletion cascades to everything
  inside it, PVC included. This is *consistent* with the design doc's stated
  intent ("a clean, total teardown"), but it does mean `helm uninstall` is
  more destructive than a Compose `down` (which explicitly preserves the
  named volume) — worth remembering before running `helm uninstall` on
  anything containing data worth keeping. A production chart wanting to
  survive uninstalls would need the `resource-policy: keep` annotation on
  the PVC specifically, or to not own the Namespace at all — neither adopted
  here, deliberately, since "no orphaned resources" was this phase's actual
  verification goal.
- Minor: had to fully tear down the raw manifests (`kubectl delete -k
  infra/k8s/`) before the first `helm install` — Helm doesn't adopt
  resources it didn't create itself, so leaving the raw manifests applied
  would have caused "already exists" conflicts on install, not a merge.

## What I'd do differently

Nothing major. Both the raw-manifest and Helm-managed installs were
independently verified with a fresh end-to-end payment test, and the
worker-pod-delete self-heal check was re-run against the Helm-managed
release as well (not just the raw manifests) — same result, a new pod
appeared automatically within about a second, confirming Helm's version
behaves identically to the hand-applied one, not just "also reaches Ready."
