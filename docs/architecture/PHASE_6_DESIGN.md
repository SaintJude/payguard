# Phase 6 Design — GitOps CD with Argo CD

## Scope

This doc covers the design for `docs/phase-notes/PHASE_6_TASKS.md`: installing
Argo CD into the existing `payguard` kind cluster, pointing it at this
repo's `main` branch, choosing which of the two Phase 5 deployment methods
becomes the one Argo-managed source of truth, transitioning the
already-running Phase 5 deployment to Argo CD's control, and proving the
GitOps reconciliation loop (push a manifest change → auto-apply; hand-edit
the cluster → auto-revert).

It specifies, concretely enough for a single implementer to build without
follow-up questions:
- Argo CD vs Flux (CLAUDE.md already names Argo CD as the default — this
  section still gives the teaching-moment tradeoff)
- Exact Argo CD install method and manifest source
- Which of `infra/k8s/` (Kustomize) vs `infra/helm/payguard/` (Helm) Argo CD
  tracks, and why
- The exact transition plan for the currently-running Phase 5 deployment
- `syncPolicy` (`automated`, `selfHeal`, `prune`) recommendation and reasoning
- The Argo CD `Application` manifest's exact shape and repo location
- Exact commands for reaching the UI, retrieving credentials, and the three
  manual-verification scenarios in `PHASE_6_TASKS.md`

This doc does **not** re-litigate anything already settled in
`docs/architecture/PHASE_5_DESIGN.md` (manifest/chart contents, namespace,
`imagePullPolicy`, resource requests/limits, probes, the PVC, or the fact
that `helm uninstall` deletes the PVC because the chart owns its own
`Namespace` object — that fact matters directly to this phase's transition
plan below, but the underlying chart design isn't reopened).

Out of scope: chaos injection and observability (Phase 7), Terraform
(Phase 8), any container registry (still deliberately unused — Argo CD reads
manifests from Git, not images from a registry; the two locally-built images
are still loaded into kind via `kind load docker-image` exactly as Phase 5
left it), and `mock-downstream`/`chaos-injector` (not built yet).

## Current-state check (performed before writing this doc)

`PHASE_6_TASKS.md`'s brief assumed the Phase 5 deployment currently running
in the cluster was raw-manifest-managed (`kubectl apply -k`), with Helm not
in the picture (`helm list -n payguard` expected empty). That assumption
turned out to be **wrong** — verified directly against the live cluster
rather than assumed:

```bash
$ kubectl get pods -n payguard
NAME                           READY   STATUS    RESTARTS      AGE
payment-api-868fbddd5d-r7zx9   1/1     Running   1 (19m ago)   19m
postgres-679d5b5896-sj4fd      1/1     Running   0             19m
redis-76ccbcffdd-9bx8h         1/1     Running   0             19m
worker-58f5f9b6b8-dpnmd        1/1     Running   0             17m

$ helm list -n payguard
NAME     NAMESPACE  REVISION  STATUS    CHART           APP VERSION
payguard payguard   1         deployed  payguard-0.1.0  1.0.0

$ kubectl get deployment payment-api -n payguard -o jsonpath='{.metadata.labels}'
{"app":"payment-api","app.kubernetes.io/managed-by":"Helm"}
$ kubectl get deployment payment-api -n payguard -o jsonpath='{.metadata.annotations}'
{"deployment.kubernetes.io/revision":"1","meta.helm.sh/release-name":"payguard","meta.helm.sh/release-namespace":"payguard"}
$ kubectl get namespace payguard -o jsonpath='{.metadata.labels}'
{"app.kubernetes.io/managed-by":"Helm","kubernetes.io/metadata.name":"payguard"}
```

**Every workload, and the `payguard` Namespace itself, is currently owned by
the Helm release `payguard`** (`meta.helm.sh/release-name` annotations,
`app.kubernetes.io/managed-by: Helm` labels, one Helm release object,
revision 1). The raw-manifest path (`kubectl apply -k infra/k8s/`) is **not**
currently applied to this cluster. This matters for the Transition Plan
below — the actual teardown command is `helm uninstall`, not `kubectl delete
-k`, regardless of which source Argo CD ends up tracking.

## Concept Primer

### What GitOps actually means, and what Argo CD's job is

"GitOps" is the practice of making a Git repository the **single source of
truth for a cluster's desired state**, and running a controller *inside* the
cluster whose whole job is to keep the cluster's *actual* state converged
with whatever's currently committed to that repo. This is a reversal of the
model Phase 5 used: there, a human ran `kubectl apply` or `helm install` —
Git described the desired state, but a person had to notice a change and
push it. Under GitOps, a controller does that noticing and pushing itself.

Argo CD is one such controller. It runs as a set of Pods inside the
cluster it manages (or a different cluster — not the case here). You give it
a `repoURL` + `path` + branch. It periodically polls that path, renders the
manifests, compares the result against what's actually running (`kubectl
diff`, conceptually), and — depending on **sync policy** (below) — either
applies the difference automatically or just reports it as `OutOfSync` and
waits for a human to click "Sync." The unit it tracks is called an
`Application`, itself a Kubernetes Custom Resource (CRD) — so "point Argo CD
at a repo" concretely means "create one `Application` object."

This is a meaningfully different failure-recovery story than Phase 5's
Deployment self-healing (a Deployment notices a *Pod* died and replaces it,
using the Deployment's own spec as ground truth). Argo CD notices when the
cluster's state diverges from **Git**, at a higher level — including someone
manually editing a Deployment's spec with `kubectl edit`, which a bare
Deployment controller would never revert on its own (Phase 5 confirmed this:
Compose's `restart: no` vs. Kubernetes' Pod-replacement behavior was about
crash recovery, not spec drift).

### `Sync` vs `Health`, and why Argo CD reports both

Every Argo CD `Application` reports two independent status fields:
- **`Sync` status** (`Synced` / `OutOfSync`): does the live cluster state
  match what's declared in Git, resource-for-resource? This is a pure diff —
  it says nothing about whether the app actually works.
- **`Health` status** (`Healthy` / `Progressing` / `Degraded` / etc.): is
  each resource in a good state by Kubernetes' own definition (a Deployment
  is `Healthy` once its desired replica count is `Ready`, a PVC is `Healthy`
  once `Bound`, etc.)?

A cluster can be `Synced` but `Degraded` (Git says "run this broken image
tag," Argo CD faithfully applied it, and now the Pod is crash-looping) —
sync correctness and application correctness are genuinely different
questions, and `PHASE_6_TASKS.md`'s verification step checks both for a
reason.

### Kustomize source vs Helm source, from Argo CD's point of view

Argo CD doesn't care which templating system produced the final YAML — it
supports both natively as `spec.source` types, auto-detected from the
target path's contents (a `kustomization.yaml` present → Kustomize; a
`Chart.yaml` present → Helm). Concretely:
- **Kustomize source**: Argo CD runs the equivalent of `kustomize build
  infra/k8s/` on every sync, gets back the same plain, resolved YAML
  `kubectl apply -k` would have produced.
- **Helm source**: Argo CD runs the equivalent of `helm template payguard
  infra/helm/payguard` on every sync, using `values.yaml` unless
  `spec.source.helm.valueFiles`/`parameters` override it. Argo CD does
  **not** create a Helm *release* object the way `helm install` does — no
  `helm.sh/release.v1` Secret gets written, `helm list` will show nothing
  once Argo CD owns this. Argo CD's own `Application` object *is* the
  release-tracking mechanism now; `helm` the CLI becomes irrelevant to this
  deployment going forward if Helm is the chosen source.

## Decision

### 1. Argo CD vs Flux

**Decision: Argo CD**, per `CLAUDE.md`'s stated default. Real tradeoff,
stated rather than skipped:

Both tools solve the same core problem (reconcile cluster state to Git) and
both would work fine for this project. They differ in shape:

- **Argo CD** centers on one large CRD, `Application`, and ships a
  first-class web UI out of the box. Its architecture is a handful of
  larger components (`api-server`, `repo-server`, `application-controller`,
  `redis`, optionally `dex` for SSO — not needed here). For a learner, the
  UI gives an immediate, visual answer to "what does 'Synced' actually look
  like" — a sync-status graph of every managed resource, one click to force
  a sync or see a diff.
- **Flux** is a set of small, single-purpose CRDs (`GitRepository`,
  `Kustomization`, `HelmRelease`, `HelmRepository`, etc.) that compose more
  the way core Kubernetes controllers do — no bundled UI (Flux ships a
  separate optional dashboard project, Weave GitOps, not installed by
  default), and it leans harder on `kubectl get`/`describe` as the primary
  interface. This is arguably the more "Kubernetes-native" feel (small CRDs,
  each doing one job, composable), at the cost of a less immediately
  visual learning experience for someone new to GitOps.

For a first GitOps setup where the explicit goal is *seeing* reconciliation
happen (`PHASE_6_TASKS.md`'s manual-verification steps all describe
watching something happen), Argo CD's UI is the more pedagogically useful
default — which is also exactly what `CLAUDE.md` already picked. Flux
remains a reasonable second GitOps tool to try later precisely because it
teaches the "small composable CRDs" model Argo CD's single `Application`
CRD doesn't.

### 2. Installation method

**Decision: plain install manifest, full install (with UI), into a
dedicated `argocd` namespace.**

```bash
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
```

- **Plain manifest vs. Argo CD's own Helm chart** (`argo-cd` in the
  `argoproj` Helm repo): the plain manifest is chosen. Installing Argo CD
  *via* Helm would add a second, unrelated layer of Helm templating on top
  of the exact tool this phase is trying to teach cleanly (Argo CD itself),
  and this phase's actual lesson is Argo CD's reconciliation behavior, not
  another round of "how do I configure this Helm chart's values.yaml."
  Per `CLAUDE.md`'s "don't over-engineer" agreement, the single `kubectl
  apply -f <url>` is the simplest correct path for a one-cluster, one-app
  learning setup.
- **Full install vs. "core" (headless, no UI)**: full install is chosen.
  Core mode drops `argocd-server` (the API/UI component) and the Redis
  cache it depends on, leaving only the application-controller — meant for
  advanced, GitOps-managing-GitOps setups (e.g. an org running Argo CD
  itself under a different, higher-trust Argo CD instance). That's not this
  project. The UI is the single best tool for a learner to *see*
  `Synced`/`Healthy` state, sync history, and live diffs — exactly the
  teaching value `PHASE_6_TASKS.md`'s verification steps are built around.
- **Public GitHub-hosted install manifest — applying Phase 4's precedent
  explicitly, not silently**: this install command pulls a manifest bundle
  from `raw.githubusercontent.com`, a GitHub-hosted URL, at apply time. This
  is the same category of judgment call `docs/architecture/PHASE_4_DESIGN.md`
  made explicitly and had the user confirm (`docs/phase-notes/PHASE_4_NOTES.md`
  line 16–17: "GitHub Actions/GHCR don't count as 'cloud provider
  dependencies'... GitHub is already the git host this project depends on
  since Phase 2"). Applying the same reasoning here: this is not AWS/GCP/
  Azure infrastructure, it's a one-time fetch of public manifests from the
  git host this project already fundamentally depends on for its own
  version control and CI. Named here explicitly, as the same precedent
  being *applied*, not re-derived from scratch.
- **`argocd` namespace**: dedicated, not `default` — same reasoning Phase 5
  gave for the `payguard` namespace (clean `kubectl get -n argocd`,
  clean total teardown via `kubectl delete namespace argocd` if ever
  needed). Argo CD's own install manifest assumes/recommends `argocd` by
  convention; no reason to deviate.
- **`argocd` CLI**: install via Homebrew (`brew install argocd`). Not
  strictly required — every verification step below has a `kubectl`
  equivalent — but it gives a faster, purpose-built way to read sync status
  and diffs (`argocd app get payguard`, `argocd app diff payguard`) once
  logged in, and it's a zero-risk, zero-cost addition (`brew install`, no
  cluster-side change). Recommended as part of this phase's setup, not
  strictly load-bearing for any verification step.

### 3. Kustomize (`infra/k8s/`) vs Helm (`infra/helm/payguard/`) as the ONE Argo CD source

**Decision: Kustomize (`infra/k8s/`) becomes the Argo-managed source of
truth.** The Helm chart stays in the repo, untouched, as a documented
reference/alternative — just not the one Argo CD tracks.

Reasoning:
- Argo CD's Kustomize integration is a direct pass-through: it runs
  `kustomize build` on `infra/k8s/` and applies exactly the YAML that comes
  out — no second templating language, no `values.yaml` resolution step to
  additionally reason about while learning Argo CD's *own* new concepts
  (`Application`, `Sync`, `Health`, `selfHeal`). Keeping this phase's new
  moving part (Argo CD) decoupled from Phase 5's already-learned moving part
  (Helm templating) keeps the lesson scoped — consistent with
  `CLAUDE.md`'s "don't over-engineer" and this project's habit of
  introducing one new concept at a time per phase.
- The existing `infra/k8s/kustomization.yaml` already lists exactly the 11
  resources Phase 5 built, unmodified — nothing needs to change in that
  directory for Argo CD to consume it as-is.
- **Currently-live status is not a deciding factor here** — see the current
  cluster-state check above: the *live* deployment right now is actually
  Helm, not raw manifests. But Decision #4 below (tear down, don't adopt)
  means Argo CD's first sync creates everything fresh regardless of which
  source is picked — so "which one is already running" doesn't reduce
  transition work either way, and doesn't override the reasoning above.
- Helm remains the better choice *in general* for multi-environment or
  multi-instance deployments (parameterizing per environment via
  `values.yaml` / `valueFiles`) — genuinely useful, just not a requirement
  this single-cluster, single-environment learning project has. If a later
  phase ever wants multiple environments (e.g. a second kind cluster running
  a "staging" values file), that's the trigger to reconsider Helm as the
  tracked source — not now.

**Namespace-object caveat, worth flagging explicitly**: `infra/k8s/
00-namespace.yaml` (the `Namespace` object) is one of the 11 resources
`kustomization.yaml` lists, so Argo CD will track and manage the `Namespace`
object itself, not just what's inside it. Combined with `prune: true`
(Decision #5), if `00-namespace.yaml` were ever removed from
`kustomization.yaml`'s resource list, Argo CD would delete the entire
`payguard` Namespace on the next sync — cascading-deleting every workload
and the PVC. This is correct, expected `prune` behavior, not a bug, but an
implementer should know it before casually editing `kustomization.yaml`'s
resource list.

### 4. Transition plan for the currently-running deployment

**Decision: (a) — tear down the current Helm release first, then let Argo
CD's initial sync create everything fresh from `infra/k8s/`.** Do not
attempt to have Argo CD "adopt" the existing Helm-managed resources.

Exact steps:
```bash
# 1. Tear down the current Helm-managed deployment. Per PHASE_5_DESIGN.md,
#    this deletes the payguard Namespace (the chart owns it) and therefore
#    the Postgres PVC and its data too — expected and acceptable: this is
#    disposable learning/test data, trivially recreated with one curl POST
#    after the new deployment is up.
helm uninstall payguard -n payguard

# 2. Confirm a clean slate before Argo CD's first sync.
kubectl get namespace payguard        # expect: not found
helm list -n payguard                 # expect: empty

# 3. Re-load images into kind if the cluster/node was otherwise untouched,
#    images loaded in Phase 5 are still present — only re-load after an
#    actual image rebuild (see PHASE_5_DESIGN.md's "re-load requirement").
kind get clusters                     # confirm `payguard` still exists
docker exec payguard-control-plane crictl images | grep payguard   # optional sanity check

# 4. Create the Argo CD Application (see Contracts below) — its first sync
#    creates the Namespace, ConfigMap, Secret, PVC, and all four workloads
#    fresh, exactly as `kubectl apply -k infra/k8s/` would have.
```

Real tradeoff against option (b), adopting the existing resources:

| | Tear down first (a) | Adopt in place (b) |
|---|---|---|
| What it requires | One `helm uninstall`, accept a data wipe | Manually reconciling Helm's ownership labels/annotations (`app.kubernetes.io/managed-by: Helm`, `meta.helm.sh/*`) so Argo CD's own ownership tracking (`app.kubernetes.io/instance` label, by default) doesn't conflict; Argo CD can genuinely end up fighting Helm's leftover metadata on first sync in ways that are confusing to debug for a first-timer |
| First-sync outcome | Deterministic: `Synced`/`Healthy` from resources Argo CD itself created | Ambiguous until proven: adoption can silently succeed, silently no-op on ownership-labeled fields, or produce a confusing partial diff depending on exact label overlap |
| Data continuity | Postgres data lost (already flagged as acceptable) | Postgres data preserved (the PVC itself isn't deleted if the Namespace isn't deleted) |
| Teaching value | Very high — this is exactly what a "first GitOps demo" should look like: predictable, inspectable, nothing left over from a different tool | Lower for a first pass — adoption is a real, useful skill, but not the *first* lesson to learn about Argo CD |

(a) is recommended because this phase's stated goal is proving the GitOps
loop works, cleanly and legibly — not preserving three test rows in
Postgres. Adoption is a legitimate technique worth learning eventually
(e.g. migrating a real, already-running production service onto GitOps
without downtime), just not the right first exercise here.

### 5. Sync policy

**Decision: `automated: {prune: true, selfHeal: true}`.**

| Flag | Value | Reasoning |
|---|---|---|
| `automated` (vs. manual sync) | **enabled** | `PHASE_6_TASKS.md` explicitly requires observing "push a manifest change → watch it reconcile automatically" — manual sync (human clicks "Sync"/runs `argocd app sync`) would require exactly the human action GitOps exists to remove. Manual sync is a legitimate choice for a production environment wanting a deliberate promotion gate — not this phase's lesson. |
| `selfHeal` | **`true`** | Directly enables `PHASE_6_TASKS.md`'s drift-correction verification step: without `selfHeal`, a `kubectl edit`/`kubectl scale` against an Argo-managed resource would just sit there as detected drift (`OutOfSync`) until a human resyncs — with it, Argo CD reverts the live edit back to match Git on its own, typically within seconds (driven by the application-controller's live Kubernetes watch on managed resources, not a Git poll). |
| `prune` | **`true`** | Without it, deleting a resource's manifest from Git leaves the corresponding cluster object orphaned forever — Argo CD would just stop tracking it, not remove it. `prune: true` makes Git deletions actually take effect in the cluster, which is the other half of "Git is the source of truth." The Namespace-object caveat in Decision #3 is the one sharp edge this introduces — worth knowing, not worth avoiding `prune` over. |

**`selfHeal: true` is a genuine behavior change from Phase 5, worth a
callout** (Concept Primer material, restated here because it's this phase's
most interesting demo step): in Phase 5, a human running `kubectl edit
deployment/worker -n payguard` to bump replicas would just... stay edited,
indefinitely, until someone next ran `kubectl apply` or `helm upgrade` and
overwrote it. There was no mechanism watching for that kind of drift at all.
With `selfHeal: true`, that same `kubectl edit` gets silently reverted by
Argo CD, usually within seconds. This is the concrete, hands-on
demonstration of "Git, not the live cluster, is now the actual source of
truth" — editing the live cluster directly stops being a reliable way to
change anything.

### 6. Reaching the Argo CD UI + initial admin credentials

The plain install manifest creates a `Service` named `argocd-server` in the
`argocd` namespace with `port: 80` (http) and `port: 443` (https), both
targeting the same container port (`8080`) on the `argocd-server` Pod — the
server itself does TLS termination and serves a self-signed cert by default,
so the `443` port is the one to forward:

```bash
kubectl port-forward svc/argocd-server -n argocd 8080:443
# then browse https://localhost:8080 (accept the self-signed-cert warning)
```

Initial admin password — auto-generated on first install, stored in a
Secret:
```bash
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d; echo
# username: admin
```

If the `argocd` CLI is installed (Decision #2):
```bash
argocd login localhost:8080 --username admin --password <decoded-password> --insecure
```
(`--insecure` because of the self-signed cert on a local install — no CA to
trust it against, and no reason to set one up for a laptop learning
cluster.)

### 7. The Argo CD `Application` manifest

**Location: `infra/argocd/application.yaml`.** Applied once by hand
(`kubectl apply -f infra/argocd/application.yaml`) as part of this phase's
setup — this is the one bootstrapping `kubectl apply` this phase still runs
manually, because it's the object that turns *everything after it* into an
automated GitOps loop. Everything downstream of this one apply (every future
change to `infra/k8s/`) is handled by Argo CD, not by hand.

Illustrative snippet (for implementer reference — not written to disk by
this design doc):

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: payguard
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/SaintJude/payguard.git
    targetRevision: main
    path: infra/k8s
  destination:
    server: https://kubernetes.default.svc
    namespace: payguard
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
```

- **`spec.project: default`**: Argo CD's built-in `AppProjects` mechanism
  lets you scope which repos/clusters/namespaces a group of Applications may
  target — genuinely useful when multiple teams/apps share one Argo CD
  instance. This project has exactly one Application; introducing a custom
  `AppProject` would be pure ceremony. `default` (created automatically by
  the install, permits any repo/any destination) is correct here.
- **`spec.destination.server: https://kubernetes.default.svc`**: the
  in-cluster API server's well-known internal DNS name — used because Argo
  CD is deploying to the *same* cluster it runs in, which is the standard,
  simplest topology and the only one this project needs (no second cluster
  exists).
- **No auth on `repoURL`**: the repo is public per this phase's brief, so
  Argo CD's `repo-server` can clone it over HTTPS with zero configured
  credentials — no deploy key, no PAT, no `Secret` of type
  `Opaque`/`repository` needed. (If the repo were ever made private, this is
  the first thing that would need to change — noted for completeness, not
  acted on.)
- **No `CreateNamespace=true` sync option**: unnecessary here — `infra/k8s/
  00-namespace.yaml` is already one of the tracked resources (see Decision
  #3's caveat), so Argo CD creates the Namespace as a normal managed
  resource, not via the sync-option shortcut meant for cases where the
  destination namespace isn't itself declared in the tracked manifests.
- **App-of-apps pattern (Argo CD managing its own install via GitOps):
  explicitly not used.** Recommend against it for this phase. An app-of-apps
  setup would have a root `Application` whose own sync target is a
  directory of *other* `Application` manifests (including, potentially, one
  managing Argo CD's own install manifests) — useful once you have several
  applications and want Git to be the source of truth for the whole fleet
  of Applications, not just their contents. With exactly one Application
  and one Argo CD install, this adds a bootstrapping problem for no benefit:
  something still has to install Argo CD and apply the *first* Application
  by hand before any of it can start managing itself, so the indirection
  buys nothing here — it only starts paying for itself with multiple apps,
  which this project doesn't have.

## Alternatives Considered & Tradeoffs

| Option | Pros | Cons | Why not chosen |
|---|---|---|---|
| Flux instead of Argo CD | More Kubernetes-native small-CRD model; no bundled UI to run/secure | No bundled UI — harder for a learner to *see* reconciliation happening; CLAUDE.md already names Argo CD as the default | Argo CD's UI better serves this phase's "watch it reconcile" teaching goal; Flux is a good second GitOps tool to learn later, not a reason to override the stated default |
| Install Argo CD via its official Helm chart (`argoproj/argo-cd`) | Consistent packaging with the rest of this project's Helm usage; easier version pinning/upgrades via `helm upgrade` | Adds a second, unrelated Helm-templating layer on top of the tool this phase is trying to teach cleanly; more to configure (`values.yaml`) for zero benefit at this scale | Plain install manifest is simpler to reason about for a first Argo CD install, per "don't over-engineer" |
| Argo CD "core" (headless, no UI, no Redis) | Smaller footprint; matches an advanced GitOps-managing-GitOps topology | No UI — removes the single best tool for a learner to see `Synced`/`Healthy` state and diffs, which this phase's verification steps are built around | Full install's UI has direct pedagogical value here; core mode is for a use case this project doesn't have |
| Helm (`infra/helm/payguard/`) as the Argo CD-tracked source | Realistic for real-world multi-environment GitOps; matches the currently-*live* deployment method (see current-state check), avoiding one layer of "switch methods" | Stacks Helm's templating indirection on top of Argo CD's own new concepts in the same phase; no multi-environment need exists yet to justify it; transition work is required either way once Decision #4 (tear-down-and-recreate) is applied, so "already live" isn't actually a savings | Kustomize source keeps this phase's new concept (Argo CD) decoupled from Phase 5's already-learned concept (Helm templating); Helm chart stays in the repo as a documented alternative |
| Adopt the existing (Helm-managed) resources into Argo CD instead of tearing down | Preserves Postgres test data; demonstrates a real "migrate an existing live service to GitOps" skill | Requires manually reconciling Helm's ownership labels/annotations against Argo CD's own tracking labels; first-sync outcome is not deterministic for a first-timer; higher risk of a confusing debugging session on this phase's very first demo | Clean teardown-and-recreate gives a deterministic, legible first GitOps demo; adoption is a real skill worth learning later, not the right first exercise |
| Manual sync (`syncPolicy` with no `automated` block) | Deliberate, human-gated promotion — closer to how a cautious production environment might run Argo CD | Directly fails this phase's explicit requirement to observe automatic reconciliation on a Git push without a human running a sync command | `PHASE_6_TASKS.md` requires automated sync as the demonstrated behavior |
| `selfHeal: false` (report `OutOfSync` on drift instead of reverting it) | Less surprising for an operator used to Phase 5's "the cluster stays however I left it" model; safer default for a shared/production cluster where an emergency `kubectl edit` might be intentional and shouldn't be silently undone | Directly fails `PHASE_6_TASKS.md`'s explicit drift-correction verification step, which requires observing an automatic revert, not just an `OutOfSync` flag | `PHASE_6_TASKS.md` requires the self-heal behavior as the demonstrated GitOps property |
| `prune: false` | Safer against accidental mass-deletion via a bad `git rm` | Leaves orphaned resources in the cluster whenever a manifest is removed from Git — undermines "Git is the source of truth" for deletions, the other half of the sync contract | `prune: true`'s one sharp edge (the Namespace-object caveat) is manageable and worth knowing rather than avoiding; a single-developer learning cluster has low blast radius for a mistaken prune |
| App-of-apps pattern (self-managing Argo CD install + Application via GitOps) | Scales cleanly to many Applications; "everything, including Argo CD's own config, is in Git" | Bootstrapping problem (something still has to install Argo CD and the root Application by hand first); pure indirection for exactly one Application | Explicitly not worth it at this project's current scale — noted as the natural next step if/when a second Application ever gets added |
| GitHub webhook (instead of polling) for near-instant Git-side reconciliation | Removes the ~3-minute default poll latency on Git-side changes entirely | Requires the local kind cluster/Argo CD's `argocd-server` to be reachable from GitHub's servers over the public internet (e.g. via `ngrok` or similar tunnel) — a real, if arguably thin, departure from "everything runs locally, no external exposure" | Polling's default ~3-minute latency is fine for a manual verification exercise; exposing a local learning cluster to the public internet isn't worth it for saving a few minutes of wait |

## Contracts

**Namespaces**: `argocd` (Argo CD's own components), `payguard` (unchanged
from Phase 5 — the Application's `spec.destination.namespace`).

**Argo CD install manifest URL** (exact, pinned to the `stable` channel —
see Open Questions for the version-pinning tradeoff):
```
https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
```

**Install commands** (exact):
```bash
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
kubectl -n argocd wait --for=condition=available --timeout=300s deployment/argocd-server
```

**Argo CD server Service** (created by the install manifest — confirm
against actual installed manifest if this ever drifts from upstream):
| Field | Value |
|---|---|
| Service name | `argocd-server` |
| Namespace | `argocd` |
| Ports | `80` (http) → containerPort `8080`; `443` (https) → containerPort `8080` |
| Port-forward command | `kubectl port-forward svc/argocd-server -n argocd 8080:443` |
| UI URL (after port-forward) | `https://localhost:8080` (self-signed cert) |

**Initial admin credentials**:
| Field | Value |
|---|---|
| Username | `admin` |
| Password | `kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" \| base64 -d` |

**GitOps source of truth**: `infra/k8s/` (Kustomize), via the existing,
unmodified `infra/k8s/kustomization.yaml`. `infra/helm/payguard/` remains in
the repo untouched, as a documented alternative — not tracked by Argo CD.

**Argo CD `Application` object**:
| Field | Value |
|---|---|
| `metadata.name` | `payguard` |
| `metadata.namespace` | `argocd` |
| `spec.project` | `default` |
| `spec.source.repoURL` | `https://github.com/SaintJude/payguard.git` |
| `spec.source.targetRevision` | `main` |
| `spec.source.path` | `infra/k8s` |
| `spec.destination.server` | `https://kubernetes.default.svc` |
| `spec.destination.namespace` | `payguard` |
| `spec.syncPolicy.automated.prune` | `true` |
| `spec.syncPolicy.automated.selfHeal` | `true` |

**Repo location for this manifest**: `infra/argocd/application.yaml`,
applied once by hand (`kubectl apply -f infra/argocd/application.yaml`) —
not itself managed by an app-of-apps Application (Decision #7).

**Transition command sequence** (exact, run in order):
```bash
helm uninstall payguard -n payguard
kubectl get namespace payguard          # expect: Error from server (NotFound)
helm list -n payguard                   # expect: empty
kubectl apply -f infra/argocd/application.yaml
```

## Manual Verification Mechanics

**(a) Confirm `Synced` + `Healthy`:**
```bash
kubectl get application payguard -n argocd
# NAME       SYNC STATUS   HEALTH STATUS
# payguard   Synced        Healthy

kubectl get application payguard -n argocd -o jsonpath='{.status.sync.status} {.status.health.status}{"\n"}'

# if argocd CLI is installed and logged in:
argocd app get payguard
```
Then confirm the payment flow itself still works end-to-end (same test as
Phase 5's Verification section — `kubectl port-forward -n payguard
svc/payment-api 8080:8080` + a `curl POST`).

**(b) Push a manifest change, watch it auto-reconcile.** Recommended
change: bump `worker`'s `replicas` from `1` to `2` in
`infra/k8s/40-worker-deployment.yaml` (`spec.replicas: 1` → `2`). This is
safe and observable: Redis Streams consumer groups already give each
message to exactly one consumer regardless of replica count (Phase 1's
design, reconfirmed as safe for scaling in `PHASE_5_DESIGN.md`'s Open
Questions), so running two `worker` replicas cannot duplicate-process a
payment or break the running flow, and `kubectl get pods -n payguard` makes
the change trivially visible (one more `worker-*` Pod appears).

Because this repo's branch protection (Phase 2) requires PRs into `main` —
no direct push, even from the repo owner — the loop is:
```bash
git checkout -b chore/bump-worker-replicas
# edit infra/k8s/40-worker-deployment.yaml: replicas: 1 -> 2
git add infra/k8s/40-worker-deployment.yaml
git commit -m "chore: bump worker replicas to 2"
git push -u origin chore/bump-worker-replicas
gh pr create --title "chore: bump worker replicas to 2" --body "Phase 6 GitOps verification: prove Argo CD auto-reconciles a manifest change."
gh pr merge --squash --delete-branch   # or merge via the GitHub UI
```
After the merge lands on `main`, watch for Argo CD to pick it up on its next
poll (default reconciliation interval is **~3 minutes** unless a webhook is
configured — not configured here, see Alternatives table):
```bash
kubectl get application payguard -n argocd -w
# watch SYNC STATUS flip OutOfSync -> Synced

kubectl get pods -n payguard -l app=worker -w
# observe: a second worker-* Pod appears, with no kubectl apply/helm upgrade
# run by hand
```

**(c) Drift correction (`selfHeal`):**
```bash
kubectl scale deployment/worker -n payguard --replicas=5
kubectl get pods -n payguard -l app=worker -w
# expect: within several seconds (driven by the application-controller's
# live watch on managed resources, not the ~3-minute Git-poll interval —
# this reaction is much faster because nothing needs to be re-fetched from
# Git, only re-compared against the already-known desired state), Argo CD
# scales it back down to whatever Git currently says (2, after step (b) —
# or 1, if run before step (b))

kubectl get application payguard -n argocd
# SYNC STATUS should read Synced again once the revert completes
```

## Open Questions

- [ ] The install manifest URL tracks Argo CD's `stable` channel
      (`.../stable/manifests/install.yaml`), which always resolves to
      whatever the current latest stable release is at install time — not a
      pinned version. This keeps the install command simple and always
      current (arguably a pro for a learning project revisited over time),
      but means re-running the install command months apart could install
      different Argo CD versions with different UI/behavior. Flagging
      rather than deciding, since it's a low-stakes call either way and
      genuinely a matter of preference (simplicity vs. reproducibility) —
      pin to a specific tag (e.g. replace `stable` with a version like
      `v2.13.2`) if reproducibility matters more than always-latest.
- [ ] Postgres data loss on `helm uninstall` (Decision #4) is treated here
      as acceptable, since it's disposable learning/test data recreated by
      one `curl POST`. Flagging explicitly in case the user has manually
      created payment records in this cluster they'd rather preserve — if
      so, back up via `kubectl exec ... pg_dump` before running `helm
      uninstall`, restore after Argo CD's first sync completes.

## Out of Scope

- Argo CD `AppProject`s beyond the built-in `default` — one Application
  doesn't need scoped project boundaries.
- App-of-apps / Argo CD managing its own install via GitOps — explicitly
  rejected for this phase's scale, see Decision #7.
- A GitHub webhook for near-instant sync (vs. default ~3-minute polling) —
  would require exposing the local kind cluster to the public internet;
  not worth it for a manual verification exercise, see Alternatives table.
- Deploy keys / private-repo auth for Argo CD's `repo-server` — the repo is
  public, no credentials are needed for read access.
- Multi-cluster Argo CD (managing a second, different cluster) — this
  project has exactly one cluster, and Argo CD is installed into it.
- SSO/Dex integration, RBAC policy configuration for Argo CD's own UI users
  — single developer, `admin` login is sufficient.
- Notifications (Argo CD Notifications controller, Slack/email on sync
  failure) — no operational audience exists yet for this learning project.
- Progressive delivery (Argo Rollouts, canary/blue-green) — a different,
  more advanced tool in the Argo ecosystem; not requested by
  `PHASE_6_TASKS.md`.
- Helm chart (`infra/helm/payguard/`) changes of any kind — it stays exactly
  as Phase 5 left it, untouched, as a documented alternative Argo CD source
  not currently tracked.
- Any container registry — still not introduced; Argo CD reconciles
  manifests, not image builds/pushes; images remain loaded into kind via
  `kind load docker-image` per Phase 5.
- `mock-downstream`/`chaos-injector` manifests — neither service exists in
  code yet.
