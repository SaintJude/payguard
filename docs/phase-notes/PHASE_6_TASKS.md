# Phase 6 Tasks — GitOps CD

Goal: install Argo CD (per `CLAUDE.md`'s default tech choice) into the
`payguard` kind cluster, point it at this repo, and prove the GitOps loop:
change a manifest in Git, push, and watch Argo CD reconcile the live cluster
to match — no `kubectl apply`/`helm upgrade` run by hand.

## Setup
- [x] Install Argo CD into the kind cluster (dedicated `argocd` namespace) —
      plain upstream install manifest, `stable` channel
- [x] Reach the Argo CD UI/API (`kubectl port-forward` — no cluster-external
      access needed, consistent with Phase 5's `payment-api` access pattern)
- [x] Retrieve/set the initial admin credentials (`argocd-initial-admin-secret`)
- [x] Create an Argo CD `Application` resource (`infra/argocd/application.yaml`)
      pointing at this repo's `infra/k8s/` path (Kustomize source — see
      design doc for why not the Helm chart)
- [x] Decide and document how the existing Phase 5 deployment transitions to
      being Argo-managed — confirmed the live deployment was actually
      Helm-managed (not raw-manifest, contrary to this file's original
      assumption), tore it down via `helm uninstall`, let Argo CD's first
      sync create everything fresh from `infra/k8s/`

## Manual verification
- [x] Argo CD reports the `payguard` Application as `Synced` and `Healthy`
      (confirmed independently: `kubectl get application payguard -n argocd`)
- [x] The four workloads in the `payguard` namespace are running and the
      payment flow still works end-to-end (same test as Phase 5) — confirmed
      independently via `kubectl port-forward` + curl, payment resolved
      `PENDING` → `COMPLETED`
- [x] Change something small in a tracked manifest (e.g. a resource limit or
      replica count), commit, push to `main`, and watch Argo CD pick it up
      and reconcile automatically within its sync interval — without anyone
      running `kubectl apply` by hand. Done via PR #10 (`chore: bump worker
      replicas to 2`, merged by the user per this repo's branch-protection
      workflow — the orchestrator opened the PR and confirmed CI green, but
      the actual merge required explicit human action, blocked by the
      permission classifier from being self-merged). Argo CD's sync revision
      matched the merge commit and a second `worker` pod appeared
      automatically within seconds of the merge — no `kubectl apply` run.
- [x] Confirm drift correction: manually `kubectl scale deployment/worker -n
      payguard --replicas=5`, then watched Argo CD's `selfHeal` revert it
      back to Git's declared `2` — reverted within ~6 seconds, the three
      extra pods shown `Terminating`, Application stayed `Synced`/`Healthy`
      throughout.

## Wrap-up
- [x] Write `docs/phase-notes/PHASE_6_NOTES.md`
- [x] Write `docs/demos/PHASE_6_DEMO.md`
- [x] Update root `README.md` and `CLAUDE.md`'s Current Phase line
- [x] Commit with message `feat: phase 6 — gitops cd`
