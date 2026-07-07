# Phase 6 Notes — GitOps CD

## What was built

- Argo CD installed into the `payguard` kind cluster (dedicated `argocd`
  namespace, plain upstream install manifest tracking the `stable` channel,
  full install including the UI).
- `infra/argocd/application.yaml` — the one `Application` resource pointing
  at this repo's `main` branch, `infra/k8s/` path, with
  `syncPolicy.automated.prune` and `.selfHeal` both `true`.
- The previously-Helm-managed Phase 5 deployment was torn down
  (`helm uninstall`) and recreated fresh by Argo CD's first sync from the
  Kustomize source — `infra/helm/payguard/` stays in the repo untouched as a
  documented alternative, not the one Argo CD tracks.
- Full rationale in `docs/architecture/PHASE_6_DESIGN.md`, including two
  decisions confirmed with the user before implementation (accept the
  Postgres data wipe from the transition; track Argo CD's `stable` channel
  rather than pinning a version).

## Surprises / gotchas

- **The architect caught a wrong assumption in the task brief before writing
  a single line.** `PHASE_6_TASKS.md`'s original draft assumed the live
  Phase 5 deployment was raw-manifest-managed. It wasn't — Phase 5's own
  implementer had left the cluster in its *Helm*-installed state as the
  final step (the last thing that agent did was `helm install` to leave a
  clean demo state). The architect verified this directly against the live
  cluster (`helm list -n payguard`, checking `meta.helm.sh/*` labels)
  instead of trusting the brief, and the whole transition plan was designed
  around the *actual* state, not the assumed one. Worth remembering: prior
  phases' "final state" isn't necessarily what a later phase's task brief
  assumes — always verify live state before designing around it.
- **The big one this phase: colima's default resource allocation (2 CPU /
  2GiB memory) was enough for Phase 3-5's four app containers, but not
  enough once Argo CD's ~6 components (server, repo-server,
  application-controller, redis, dex-server, applicationset-controller,
  notifications-controller) were added on top.** Symptoms: Postgres stuck
  `Pending` (unschedulable — insufficient memory), Redis stuck
  `ContainerCreating`, and every pod (including Argo CD's own) accumulating
  double-digit restart counts from what looked like transient image-pull
  failures and probe timeouts but was actually memory pressure causing OOM
  kills and a generally starved node. The first implementer agent dispatched
  for this phase's cluster-side work actually stalled entirely (its
  background task hit a 600-second no-progress watchdog) — almost certainly
  because the resource-starved cluster made its `kubectl` commands hang
  rather than fail cleanly, which is a worse failure mode than an outright
  error. Fix: `colima stop` + `colima start --cpu 4 --memory 6` (host has 10
  cores / 16GB, so this left plenty of headroom). The kind node's Docker
  container survived the colima restart automatically (Docker's own
  restart-policy handling), and cluster state (`argocd`/`payguard`
  namespaces, the Application object, Kustomize-applied resources) was all
  still there once the VM came back — no need to recreate the kind cluster
  from scratch, just wait for pods to reschedule with the new headroom.
- **Once Argo CD was actually installed and connected**, everything else
  worked essentially exactly as the design doc predicted, with no further
  surprises: initial sync reached `Synced`/`Healthy` promptly, the
  push-a-manifest-change loop showed a new pod appearing within seconds of
  the PR merge (matching the design doc's note that a *new* Application's
  first sync and *subsequent* change-detection both tend to be faster in
  practice than the documented ~3-minute default poll interval — this
  cluster never demonstrated that worst-case latency), and `selfHeal`
  reverted a manual `kubectl scale` to 5 replicas back down to Git's
  declared `2` within about 6 seconds.
- **Self-merging a PR got blocked by the permission system, as designed.**
  The orchestrator opened PR #10 (the worker-replicas bump needed for this
  phase's GitOps demo) and confirmed CI green, but `gh pr merge` was denied
  by the auto-mode classifier — merging without evidence of human review is
  exactly the kind of action that policy exists to catch, even for a small,
  low-risk chore commit whose entire purpose was a verification exercise.
  The user merged it manually. Worth remembering for any future phase whose
  verification steps require a real merge to `main`: budget for a
  human-in-the-loop pause at that exact step, don't assume it can be fully
  automated end-to-end even when every other step can be.

## What I'd do differently

Bump colima's resources proactively before Phase 6 even started, rather than
reactively after hitting `Pending` pods and a stalled agent. Argo CD's
resource footprint (6 components, several with their own Redis/gRPC
dependencies) is well-documented as heavier than a single app's — worth
treating "am I about to add a multi-component control-plane tool to this
cluster" as a signal to check/raise VM resources first, not after something
starts failing in a confusing way.
