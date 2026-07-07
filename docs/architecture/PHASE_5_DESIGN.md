# Phase 5 Design — Local Kubernetes with kind, then Helm

## Scope

This doc covers the design for `docs/phase-notes/PHASE_5_TASKS.md`: standing
up a local `kind` cluster, translating `docker-compose.yml`'s four services
(`payment-api`, `worker`, `postgres`, `redis`) into plain Kubernetes
manifests under `infra/k8s/`, verifying the system end-to-end on that
cluster, and then re-packaging the same manifests as a Helm chart under
`infra/helm/`.

It specifies, concretely enough for a single implementer to build without
follow-up questions:
- kind cluster name/config, single-node
- The exact mechanism for getting the two locally-built images into kind
  (this phase's single biggest gotcha)
- `Deployment` + `Service` for all four workloads, with ports, probes,
  resource requests/limits, `imagePullPolicy`
- `ConfigMap`/`Secret` split for connection info and credentials
- Startup-ordering approach given Kubernetes has no `depends_on`
- `PersistentVolumeClaim` for Postgres — storage class, access mode, size
- Namespace recommendation
- Manifest file organization under `infra/k8s/`
- Helm chart structure under `infra/helm/`, what `values.yaml` parameterizes
- Exact manual-verification commands

This doc does **not** re-litigate anything already settled in
`docs/architecture/PHASE_3_DESIGN.md` (Dockerfile contents, base images,
env var names, healthcheck *commands*, the `depends_on` graph as a concept)
or Phase 1's schema-ownership rule (payment-api owns Flyway; worker never
migrates) — it translates those decisions into Kubernetes primitives.

Out of scope: GitOps/Argo CD (Phase 6), chaos injection and observability
(Phase 7), Terraform (Phase 8), any container registry (GHCR or otherwise —
Phase 4 deliberately deferred registry push; this phase does not reopen
that), and `mock-downstream`/`chaos-injector` (not built yet).

## Concept Primer

### What a local Kubernetes cluster actually is, and what `kind` does

Kubernetes is normally a fleet of real or virtual machines: one or more
**control-plane** nodes (the API server, scheduler, etcd) and one or more
**worker** nodes (where your containers actually run). Getting a fleet like
that on a laptop for learning purposes would mean either paying a cloud
provider or wrangling a multi-VM setup by hand.

`kind` ("Kubernetes IN Docker") sidesteps this: it runs an entire Kubernetes
node — control plane and all — *inside a single Docker container*. A
"single-node" kind cluster is really one Docker container pretending to be
both the control plane and the only worker node, running `containerd`
(Kubernetes' preferred container runtime) internally. `kubectl` then talks
to the Kubernetes API server exposed by that container exactly as it would
talk to a real cluster — the workloads you deploy have no idea they're
nested inside Docker.

This is why kind is "no cloud provider dependency" and fully local: the only
thing it needs is a working Docker daemon (colima, per Phase 3's notes) and
Kubernetes never leaves your machine.

### The image-visibility gotcha (read this before anything else)

This is the thing that trips up almost everyone new to kind, and it matters
immediately for this phase.

When you run `docker compose build` (or `docker build`) on your Mac, the
resulting image is stored in the **host's Docker daemon** — the one colima
runs. That's the same daemon `docker images` on your Mac talks to.

kind's Kubernetes node is *itself* a Docker container, and inside that
container runs its own separate `containerd`, with its own separate image
store. When a Kubernetes `Pod` spec says `image: payguard-payment-api`,
the kubelet inside the kind node asks *that* node's containerd "do you have
this image?" — and the answer is no, because nothing ever copied it in.
containerd has no automatic visibility into the host Docker daemon's images,
even though both happen to be "Docker" in some sense. Left unaddressed, every
pod using a locally-built image sits in `ImagePullBackOff`, because
Kubernetes' default behavior when it can't find an image locally is to try
pulling it from a registry (e.g. Docker Hub) — and `payguard-payment-api`
doesn't exist on any registry, since Phase 4 deliberately never pushed it
anywhere.

Three ways to solve this exist in general (expanded in Alternatives below);
this design picks the simplest one for a local, no-registry, single-developer
workflow: **`kind load docker-image`**, a purpose-built kind command that
copies an image directly from the host Docker daemon into the kind node's
containerd store, no registry involved at all.

### Kubernetes objects this phase introduces

- **Deployment**: a declarative spec for "keep N replicas of this pod
  running." If a pod crashes or is deleted, the Deployment's controller
  notices the actual state no longer matches the desired state and creates a
  replacement — this is the mechanism behind this phase's "delete the worker
  pod and watch it come back" verification step, and it's a real behavioral
  difference from Compose's `restart: no` default (deliberately chosen in
  Phase 3 so crashes stayed *visible*). Kubernetes' philosophy is different:
  a Deployment's job is to keep the *replica count* correct, not to leave
  evidence of a crash lying around. (Phase 7's chaos work is a more natural
  place to reconcile "notice failures" with "self-heal automatically" — not
  this phase's concern.)
- **Service**: a stable network name + IP for a set of pods, selected by
  label. Pods are ephemeral (a new one gets a new IP on every restart);
  a Service gives other pods (or `kubectl port-forward`) something constant
  to point at, and does simple load-balancing across whichever pods
  currently match its selector.
- **ConfigMap**: a bag of non-secret key/value config, injectable into a pod
  as environment variables or mounted files.
- **Secret**: structurally almost identical to a ConfigMap, but flagged as
  sensitive — see the Decision section for exactly what that does and does
  not buy you locally.
- **PersistentVolumeClaim (PVC)**: a request for durable storage that
  outlives any single pod, analogous to Compose's named volume. A PVC is
  bound to a **PersistentVolume**, which is actually provisioned by a
  **StorageClass** — kind ships a default one (`standard`) that backs
  volumes with a directory on the kind node's own filesystem (itself inside
  the Docker container, but that container's filesystem persists across pod
  restarts, which is all this phase needs).
- **Probes** (readiness/liveness): commands or HTTP calls Kubernetes runs
  *inside* a pod on an interval, same idea as Compose's healthcheck but with
  two distinct purposes instead of one — see the Startup Ordering section.

### Startup ordering: Kubernetes has no `depends_on`

Compose's `depends_on: condition: service_healthy` is a **blocking**
primitive: it delays starting a container until a dependency reports
healthy. Kubernetes deliberately has no equivalent for Deployments. This
isn't an oversight — it reflects a different philosophy. Compose orchestrates
a fixed, small set of containers meant to come up together once. Kubernetes
is built for a world of pods that get rescheduled, rescaled, and restarted
independently and constantly, often on different nodes, often owned by
different teams — a static "wait for X" graph doesn't generalize to that
world. Instead, Kubernetes expects **every pod to tolerate its dependencies
being temporarily unavailable** and either retry internally or fail its own
readiness probe until conditions improve — the same resilience pattern this
whole project exists to teach, just applied to the platform itself instead
of application code.

Concretely for PayGuard: `worker`'s pod is allowed to start before
`payment-api` has run its Flyway migration. Nothing forces an ordering. What
actually makes this safe is the same thing Phase 1 already relies on:
Spring's JDBC and Redis clients retry failed connections rather than
crashing the JVM outright on the first attempt. Where the `payments` table
genuinely doesn't exist yet, `worker`'s DB calls will fail and (per Phase 1's
retry/backoff design) get retried — see Decision for the concrete
recommendation on whether to also add an `initContainer` as a belt-and-braces
measure.

## Decision

### kind cluster

- **Cluster name**: `payguard` (matches `CLAUDE.md`'s existing example
  command, `kind create cluster --name payguard`, so nothing here contradicts
  already-documented usage).
- **Topology**: single-node (control-plane node doubles as the only worker
  node — this is kind's default when no config file is given). No HA
  requirement exists for a solo learning project; a multi-node kind cluster
  would only add scheduling complexity with zero corresponding lesson value
  at this phase.
- **Custom kind config**: not needed. kind's default single-node cluster
  already exposes the API server to `kubectl` on the host, and this phase
  reaches `payment-api` via `kubectl port-forward` (see Verification below),
  which works against any Service/Pod regardless of kind's node port mapping
  — so no `extraPortMappings` block is required. (If a later phase wants a
  cluster-external URL without `port-forward` running, that's the trigger to
  revisit a custom config — not now, per "don't over-engineer.")
- **Create command**: `kind create cluster --name payguard`.

### Getting images into kind

- **Mechanism**: `kind load docker-image`, run once per image after every
  `docker compose build`.
- **Image names/tags**: reuse exactly what `docker compose build` already
  produces — confirmed on this machine via `docker images`:
  `payguard-payment-api:latest` and `payguard-worker:latest`. (Compose v2
  names images `<project>-<service>`, where the project name defaults to the
  lowercased directory name — `payguard` here. No new tagging scheme is
  introduced; manifests reference these tags directly.)
- **Exact commands** (run from repo root, after `docker compose build`):
  ```bash
  docker compose build payment-api worker
  kind load docker-image payguard-payment-api:latest --name payguard
  kind load docker-image payguard-worker:latest --name payguard
  ```
- **Re-load requirement**: `kind load docker-image` is a one-time copy, not
  a live link — every time the image is rebuilt (code change), it must be
  reloaded, then the Deployment's pods must be recreated to actually pick up
  the new image content (`kubectl rollout restart deployment/payment-api`,
  since the tag itself doesn't change and Kubernetes won't otherwise notice
  new bytes behind the same tag — see `imagePullPolicy` below for why this
  matters).

### Deployment / Service manifests

**`imagePullPolicy: Never`** on `payment-api` and `worker` (not
`IfNotPresent`). Reasoning: `Never` makes the failure mode explicit and
immediate — if the image genuinely isn't loaded into the node yet (a
`kind load` step was skipped), the pod fails fast and visibly
(`ErrImageNeverPull`) instead of silently succeeding via a leftover stale
image. `IfNotPresent` would technically also work here since these images
are never registry-hosted, but it reads as "try to pull if missing," which
implies registry behavior that doesn't apply to this workflow and could
mislead a reader of the manifest into thinking a registry pull is possible.
`imagePullPolicy: Always` would actively break this setup: it forces
Kubernetes to attempt a registry pull on every pod (re)start regardless of
whether a local image already exists on the node, and `payguard-payment-api`
has no registry to pull from — every pod restart would fail.

**Postgres and Redis** use their official upstream images
(`postgres:16-alpine`, `redis:7-alpine`, matching Phase 3's Decision exactly)
pulled normally from Docker Hub — `imagePullPolicy` is irrelevant to name
explicitly here since default (`IfNotPresent`) is correct and these images
genuinely are meant to come from a public registry.

**Container ports** (matches Phase 3's Dockerfile `EXPOSE`/no-`EXPOSE`
contract exactly):

| Workload | containerPort | Notes |
|---|---|---|
| `payment-api` | 8080 | HTTP surface |
| `worker` | none | No HTTP surface — no `ports:` block in its Deployment |
| `postgres` | 5432 | Standard Postgres port |
| `redis` | 6379 | Standard Redis port |

**Service types**: `ClusterIP` for all four (Kubernetes' default type, and
also what you get by simply omitting `type:`). None of these need to be
reachable from outside the cluster by anything other than the developer
manually testing — `kubectl port-forward` opens a direct tunnel from a port
on the host into a specific pod or Service, bypassing the need for a
cluster-external entry point entirely. `NodePort` (which opens a static port
on the node itself) and `LoadBalancer` (which asks the cloud provider to
provision an external load balancer) both solve "let traffic in from outside
the cluster without a human running a command" — a real requirement for a
production service with real users, not for a single developer manually
poking at their own local cluster. `LoadBalancer` in particular has no
meaning at all in a bare kind cluster without extra tooling
(`cloud-provider-kind` or MetalLB) since there's no cloud provider to ask —
correctly out of scope here per the no-cloud-dependency rule anyway.

**Resource requests/limits**: set on all four workloads, deliberately
modest. Reasoning: even in a learning project, setting *some* value teaches
a real and important concept — without a `requests` value, the Kubernetes
scheduler has no idea how much room a pod needs and can pack too many pods
onto too little real memory/CPU; without a `limits` value, a single runaway
container (e.g. a memory leak) can starve every other pod on the node. This
project's entire premise is *noticing* misbehavior, and having no
requests/limits at all silently defeats one of the mechanisms that would
otherwise surface a resource problem (the pod would simply be OOM-killed
with a visible `OOMKilled` status once it exceeds `limits.memory`, rather
than degrading the whole node invisibly).

| Workload | requests.cpu | requests.memory | limits.cpu | limits.memory |
|---|---|---|---|---|
| `payment-api` | 100m | 256Mi | 500m | 512Mi |
| `worker` | 100m | 256Mi | 500m | 512Mi |
| `postgres` | 100m | 128Mi | 500m | 256Mi |
| `redis` | 50m | 64Mi | 200m | 128Mi |

(JVM defaults heap sizing to a fraction of the container's visible memory
since JDK 21 is cgroup-aware, so 256Mi/512Mi is enough headroom for these
small Spring Boot services on a laptop without inviting an OOM-kill under
normal load; these are starting points, not tuned figures — revisit if
Phase 7's load/chaos work shows they're wrong.)

### ConfigMap + Secret split

| Value | Where | Why |
|---|---|---|
| `SPRING_DATASOURCE_URL` | ConfigMap | A hostname/port/db-name string, not sensitive on its own |
| `REDIS_HOST` | ConfigMap | Same — a DNS name (the Redis Service name), not sensitive |
| `REDIS_PORT` | ConfigMap | Same — a fixed, well-known port number |
| `DB_USERNAME` | Secret | Treated as a credential, consistent with Phase 3 already separating it into its own env var rather than baking it into the URL |
| `DB_PASSWORD` | Secret | Same — a credential |
| `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB` (Postgres container's own env) | Secret for user/password, ConfigMap for `POSTGRES_DB` | `POSTGRES_DB` is just a database name (not sensitive); user/password mirror the values above and **must match them exactly**, same constraint Phase 3's Contracts section already stated for Compose |

**What a Kubernetes Secret actually protects against, and what it does not,
locally** — worth being explicit about, since this is easy to overstate:

- A Secret's values are stored **base64-encoded**, not encrypted. Base64 is
  an *encoding* (reversible with zero secret material needed — `echo
  <value> | base64 -d` on anyone's laptop, no key required), not
  *encryption*. Anyone who can run `kubectl get secret -o yaml` (i.e.
  anyone with API access to this cluster, which on a solo laptop is just
  you) can trivially decode it. On a real multi-tenant cluster, the value
  of a Secret over a ConfigMap comes from **RBAC** (Role-Based Access
  Control — you can grant "read ConfigMaps" without granting "read
  Secrets" to a given user/service account) and from **encryption-at-rest**
  in etcd, which a real cluster operator can enable — kind's default etcd
  is not encrypted at rest.
- Concretely on this project: a Secret here buys **zero** additional
  protection beyond a ConfigMap, because the credentials are hardcoded
  `payguard`/`payguard` (already flagged as an accepted gap in Phase 3's
  Out of Scope — "Secrets management ... is a later-phase concern"), there
  is exactly one human with access to this laptop, and no RBAC policy is
  configured to differentiate access to Secrets vs. ConfigMaps anyway. Using
  a Secret here is purely a **modeling/habit exercise** — it puts the right
  *kind* of value in the right *kind* of object, which is the muscle memory
  that matters once a real cluster (with real RBAC, real encryption-at-rest,
  and a real external secrets manager) enters the picture. It is explicitly
  not claimed to make this local setup any more secure than a ConfigMap
  would.
- `kind: Secret` with `type: Opaque` is the right type here (a generic
  key/value secret) — not `kubernetes.io/basic-auth` or similar
  purpose-built types, since these values are consumed as arbitrary env vars
  by Spring Boot, not by a Kubernetes-native auth mechanism.

### Startup ordering: probes + retry, no `depends_on` equivalent needed as a blocker

**Readiness probes** (translating Phase 3's healthcheck commands 1:1):

| Workload | Readiness probe | Liveness probe |
|---|---|---|
| `postgres` | `exec`: `pg_isready -U payguard -d payments` | Same command, longer `periodSeconds`/`failureThreshold` — restart only on sustained failure |
| `redis` | `exec`: `redis-cli ping` | Same command |
| `payment-api` | `httpGet`: path `/`, port `8080` | Same, more tolerant timing |
| `worker` | none — worker has no HTTP surface and nothing in this system depends on worker's own readiness (same reasoning Phase 3 used to skip a worker healthcheck entirely) | `exec`: a trivial liveness check isn't meaningful here either without an HTTP surface or a JVM-process-check; **omit** — an unrecoverable worker crash (e.g. Phase 1's daemon-thread bug) will show up as the container exiting and the Deployment restarting it, which is itself visible in `kubectl get pods` |

Readiness (not just liveness) matters here specifically because a Service
only routes traffic to pods that report Ready — so even though nothing
*blocks* `worker`'s pod from starting before `payment-api` is ready, `redis`
and `postgres`'s own readiness probes mean their Services won't actually
accept connections from anyone until `pg_isready`/`redis-cli ping` succeed,
which narrows (without eliminating) the window where a dependent's first
connection attempt would fail.

**What actually handles ordering**: Spring Boot's JDBC connection pool
(HikariCP) and Lettuce (Spring Data Redis's client) both retry failed
connection attempts by default rather than crashing the JVM on the first
failure — the same behavior Phase 1's retry/backoff design already leans on
for the downstream-call path, just now also covering the startup path. If
`worker`'s pod happens to start before `payment-api`'s Flyway migration has
run, its first few DB calls fail and get retried; once the table exists,
subsequent calls succeed. This is the Kubernetes-native version of "tolerate
your dependency not being ready yet," replacing Compose's blocking
`depends_on: condition: service_healthy`.

**initContainer recommendation: do not add one.** An `initContainer` (e.g.
a `busybox` loop polling `pg_isready` before the main `worker` container
starts) is a real, commonly-used Kubernetes pattern, and it would tighten
the startup-ordering window further. But it's not recommended here: it adds
a second copy of the "wait for Postgres" logic that has to be kept in sync
with the readiness probe's own command, adds a new image dependency
(`busybox` or similar) and a new manifest field to explain, and papers over
exactly the resilience behavior (retry-on-connection-failure) this project
is trying to make visible rather than hide. Per `CLAUDE.md`'s
"don't over-engineer" agreement and Phase 3's identical call on
`restart: unless-stopped` (rejected for the same "don't paper over failure
modes" reasoning), this is judged not worth the added surface for a system
that already retries correctly without it. Revisit only if manual testing
in this phase's Verification step actually shows worker's startup retries
misbehaving in a way an initContainer would meaningfully fix.

### PersistentVolumeClaim for Postgres

- **StorageClass**: `standard` — kind's built-in default (backed by the
  [`rancher.io/local-path`](https://github.com/rancher/local-path-provisioner)
  provisioner kind installs automatically), confirmed present with
  `kubectl get storageclass`. No explicit `storageClassName:` is strictly
  required (a PVC with no `storageClassName` uses whichever StorageClass is
  marked default, and kind marks `standard` default out of the box), but
  the manifest should still name it explicitly (`storageClassName:
  standard`) for the same reason Phase 3 pinned exact base image tags
  instead of relying on an implicit default — explicit beats implicit for
  something an implementer or future reader needs to not have to go verify.
- **Access mode**: `ReadWriteOnce` — the volume only needs to be mounted
  read-write by a single pod at a time (one Postgres replica), which is also
  the only access mode kind's `local-path` provisioner actually supports.
- **Size**: `1Gi`. Generous for a learning project's data volume (a handful
  of test payment rows) while staying trivially small on a laptop's disk.
- **Mount path**: `/var/lib/postgresql/data` — identical to Phase 3's named
  volume, so no Postgres-side config changes.

### Namespace

**Recommendation: a dedicated `payguard` namespace**, not `default`.

| | |
|---|---|
| Cost | One extra manifest (`Namespace`) and one extra `-n payguard` / `--namespace payguard` flag (or a `kubectl config set-context --current --namespace=payguard` to make it the default for the current context) on every subsequent command |
| Benefit | Teaches a real, universally-used concept (namespaces are how real clusters partition workloads — by team, by environment, by app) at essentially zero cost; keeps `kubectl get pods` output free of noise if this same kind cluster is ever reused for another experiment later (e.g. trying out an unrelated tool); makes `kubectl delete namespace payguard` a clean, total teardown of everything this phase creates, without needing to enumerate every resource by hand |

Concretely: create the namespace first (`infra/k8s/00-namespace.yaml`), and
every other manifest sets `metadata.namespace: payguard` (or the
implementer applies everything with `kubectl apply -n payguard -f ...`,
either works — the manifests should still declare the namespace explicitly
inside each file rather than relying on `-n` being remembered forever).

### Manifest file organization under `infra/k8s/`

**One file per Kubernetes object, prefixed with the workload it belongs to**
— not one giant file, not one file per resource *type*. Concretely:

```
infra/k8s/
├── 00-namespace.yaml
├── 01-configmap.yaml
├── 02-secret.yaml
├── 10-postgres-pvc.yaml
├── 11-postgres-deployment.yaml
├── 12-postgres-service.yaml
├── 20-redis-deployment.yaml
├── 21-redis-service.yaml
├── 30-payment-api-deployment.yaml
├── 31-payment-api-service.yaml
├── 40-worker-deployment.yaml
```

(`worker` has no Service — no HTTP surface, matching Phase 3's Contracts
table.)

Reasoning: numeric prefixes make `kubectl apply -f infra/k8s/` (which
applies files in directory-listing order) apply roughly dependency-first —
namespace and config before workloads, Postgres before the services that
depend on it — even though, per the Concept Primer, nothing *requires* this
ordering to succeed (readiness/retry handles the rest). It's a readability
aid for a human skimming `ls infra/k8s/`, not a correctness mechanism. One
file per object (rather than combining a Deployment+Service per workload
into one file with `---` separators) keeps `git diff` scoped to exactly the
object that changed, and keeps each file small enough to read in one
screen — consistent with Phase 3's one-Dockerfile-per-service granularity.

## Helm chart structure

### Concept Primer: what Helm actually is

A **Helm chart** is a directory of Kubernetes manifest *templates* (using Go
template syntax: `{{ .Values.someKey }}`) plus a `values.yaml` file
supplying the actual values those placeholders resolve to. `helm template`
renders the templates into plain YAML without touching a cluster (useful for
reviewing exactly what would be applied); `helm install <release-name>
<chart>` renders and applies it in one step, tracking everything it created
as a named **release**; `helm upgrade` re-renders with new values/template
changes and diffs against the currently-deployed release; `helm uninstall`
deletes every resource that release owns. The core value proposition over
raw `kubectl apply -f`: a single chart can be installed multiple times with
different values (e.g. different environments), and Helm tracks *release
state* so `upgrade`/`uninstall`/`rollback` are safe, whole-release
operations instead of the developer manually tracking which `kubectl apply`
created what.

### Decision: hand-write the chart from the raw manifests; do not use `kompose`

`kompose` can auto-convert a `docker-compose.yml` directly into Kubernetes
manifests or a Helm chart, and it's a legitimate tool for a team that just
wants compose-to-k8s translation done fast. It is deliberately **not** used
here, for the same reason Phase 1 chose a hand-rolled Redis consumer loop
over Spring's declarative `@RedisListener`-style abstraction: this project's
explicit purpose is to learn the mechanics, not to get a working system as
fast as possible by hiding them behind a generator. Auto-generated
manifests/charts also tend to encode a generic, one-size-fits-all shape
(e.g. `kompose` typically emits a `PersistentVolumeClaim` for *every*
service with a volume, defaults every Service to whatever type it guesses,
and doesn't know this project's specific ConfigMap/Secret split) that would
need just as much manual review and correction as writing it by hand — with
the added cost of first understanding someone else's generated output.
Hand-writing the raw manifests in the step before this one, then converting
those *specific, already-understood* manifests into a chart, keeps every
decision traceable to a reason stated in this doc.

### Chart name and location

`infra/helm/payguard/` — chart name `payguard`, matching the project name
and the namespace decided above (Helm has no requirement that these match,
but doing so keeps `helm install payguard infra/helm/payguard -n payguard`
readable rather than introducing a third, different name).

### Chart layout

```
infra/helm/payguard/
├── Chart.yaml
├── values.yaml
└── templates/
    ├── namespace.yaml
    ├── configmap.yaml
    ├── secret.yaml
    ├── postgres-pvc.yaml
    ├── postgres-deployment.yaml
    ├── postgres-service.yaml
    ├── redis-deployment.yaml
    ├── redis-service.yaml
    ├── payment-api-deployment.yaml
    ├── payment-api-service.yaml
    └── worker-deployment.yaml
```

Same one-file-per-object shape as the raw manifests, for the same
readability/`git diff` reasons — the only change per file is wrapping
literal values in `{{ .Values.* }}` template expressions and adding the
standard `{{ .Release.Name }}`/`{{ .Chart.Name }}` labels Helm convention
expects (a trimmed-down version of what `helm create`'s scaffold would
generate, not the full scaffold — this project doesn't need `_helpers.tpl`
named-template indirection, a `NOTES.txt`, or a `tests/` chart-test
directory for four workloads).

### What `values.yaml` parameterizes

The rule: parameterize things that plausibly vary **between deployments of
the same chart** (a rebuild with a new image tag, a resource-limit tuning
pass), not things that are **structural** to the application itself (a port
number the app is hardcoded to listen on, an env var *name* Spring Boot
expects — those live in the template files as literals, because changing
them would require a code change anyway, not a values-file edit).

| `values.yaml` key | Example | Why parameterized |
|---|---|---|
| `paymentApi.image.repository` / `.tag` | `payguard-payment-api` / `latest` | Changes on every rebuild during active development |
| `worker.image.repository` / `.tag` | `payguard-worker` / `latest` | Same |
| `paymentApi.replicaCount` | `1` | Plausible to scale later (though see Open Questions) |
| `worker.replicaCount` | `1` | Same |
| `paymentApi.resources` / `worker.resources` / `postgres.resources` / `redis.resources` | requests/limits table above | Reasonable to tune per-machine (a more powerful/less powerful laptop) without touching a template |
| `postgres.persistence.size` | `1Gi` | Plausible to grow |
| `postgres.credentials.username` / `.password` | `payguard` / `payguard` | Still hardcoded defaults in `values.yaml` (no secrets-management upgrade implied — see Out of Scope), but factored out so a reader immediately sees this is *the* place credentials live, one level up from being buried in a template |

**Not** parameterized (stay as literals in the templates): container ports
(8080/5432/6379 — structural, match the app's own `application.yml`/image
`EXPOSE`), Service type (`ClusterIP` — a deliberate architectural decision
from this doc, not a per-deployment tuning knob), the `REDIS_HOST`/`
SPRING_DATASOURCE_URL` env var *names* (Spring Boot's binding contract,
fixed by the application code), namespace name (one namespace, one chart,
per this doc's Decision — introducing a `namespace` value would only matter
for a multi-environment use case this project doesn't have).

## Alternatives Considered & Tradeoffs

| Option | Pros | Cons | Why not chosen |
|---|---|---|---|
| Multi-node kind cluster (e.g. 1 control-plane + 2 workers) | Closer to a "real" cluster topology; exercises pod scheduling across nodes | No HA requirement for a solo laptop project; more moving parts (which node did my pod land on?) for zero corresponding lesson value at this phase | Single-node matches this project's actual need and CLAUDE.md's "don't over-engineer" agreement |
| Push images to a local registry container (e.g. `registry:2` on `localhost:5000`) and let kind pull from it | Closer to how a real multi-node cluster gets images (registries are the standard mechanism); reusable if Phase 6 wants pull-based GitOps sooner | Extra moving part (a registry container to run and keep alive) for a single-node cluster that doesn't need registry-style distribution; `kind load docker-image` already solves the actual problem with one command per image | `kind load docker-image` is simpler and sufficient for single-node, no-GitOps-yet; revisit when Phase 6 wants a registry pull anyway |
| Mount the host's Docker socket into the kind node so it shares the host's image store directly | No explicit load step at all | Not how kind is designed to work (kind nodes run their own containerd, not a proxy to the host Docker daemon) — this would require nonstandard, unsupported node configuration for no real benefit over the one-line supported command | `kind load docker-image` is the standard, supported, documented mechanism for exactly this use case |
| `imagePullPolicy: IfNotPresent` instead of `Never` | Slightly more forgiving — pod would still start if an image happens to already be cached from a prior load, even after `kind delete cluster`/recreate in a weird edge case | Blurs the mental model by implying a registry pull is a live fallback path, when none exists for these two images — a stale image bug (forgot to reload after a rebuild) surfaces less obviously | `Never` fails fast and matches this project's "notice failures, don't paper over them" pattern from Phase 3 |
| NodePort Service for `payment-api` instead of ClusterIP + port-forward | Reachable via a fixed `localhost:<port>` without a `kubectl port-forward` process needing to stay running | Adds a static port allocation to reason about; no real benefit over `port-forward` for a single developer manually testing, since `port-forward` is already a single, disposable command | `port-forward` is the standard, zero-extra-manifest way to reach a ClusterIP Service for manual testing |
| Secret for all env vars (including `REDIS_HOST`/`SPRING_DATASOURCE_URL`), skip ConfigMap entirely | Simpler mental model (one object type instead of two) | Misuses Secret for genuinely non-sensitive values, teaching the wrong lesson about what Secrets are for; makes `kubectl get configmap` less useful as a quick "what's my non-secret config" check | The ConfigMap/Secret split is itself the concept worth learning here — collapsing it defeats the purpose |
| `initContainer` wait-for-postgres on `worker` | Tighter startup-ordering guarantee; a commonly-used real-world pattern worth knowing | Duplicates the readiness probe's own check in a second place; hides rather than exercises the retry-on-connection-failure behavior this project wants visible; adds an image dependency and manifest complexity | Spring's own connection retry already handles this safely; adding the initContainer is over-engineering for a system that already works without it, per the Decision section's detailed reasoning |
| `kompose convert` to auto-generate manifests/chart from `docker-compose.yml` | Fast; less manual YAML to write; battle-tested tool | Hides the exact mechanics (Deployment/Service/ConfigMap/Secret construction) this project exists to teach; generated output still needs manual review/correction for this project's specific ConfigMap/Secret split and namespace decision, so the time saved is smaller than it looks | Directly contradicts the precedent set in Phase 1 (hand-rolled Redis consumer over Spring's declarative listener) — this project consistently favors visible mechanics over generator convenience |
| Full `helm create` scaffold (with `_helpers.tpl`, `NOTES.txt`, `tests/`, `serviceaccount.yaml`, ingress template, HPA template, etc.), then delete what's unused | Matches exactly what `helm create` produces, so it "looks like" a standard chart to anyone who's used Helm before | Most of the scaffold (ingress, HPA, service account, chart tests) has no use in this phase and would need to be explained-then-deleted, or left in as unused dead weight — either way it's ceremony without a lesson for four workloads | A trimmed-down, hand-written chart covering exactly this project's four workloads teaches the concepts that matter (templates + values + release lifecycle) without unused scaffold noise |
| Parameterize container ports / env var names in `values.yaml` | Maximum flexibility — chart could theoretically deploy a completely different app shape | These are structural to the application code (Spring Boot's `server.port`, its env-var binding contract), not deployment-time config — changing them without a matching code change would break the app | Per this doc's stated rule: parameterize what varies between deployments, not what's fixed by the code |

## Contracts

**Files this design specifies** (created by the implementer; none created by
this doc):
- `infra/k8s/00-namespace.yaml` through `infra/k8s/40-worker-deployment.yaml`
  (exact list in Manifest File Organization above)
- `infra/helm/payguard/Chart.yaml`
- `infra/helm/payguard/values.yaml`
- `infra/helm/payguard/templates/*.yaml` (exact list in Chart Layout above)

**kind cluster name**: `payguard`.

**Image names/tags** (must match `docker compose build`'s actual output
exactly — verified via `docker images` on this machine):
| Image | Tag |
|---|---|
| `payguard-payment-api` | `latest` |
| `payguard-worker` | `latest` |

**`kind load docker-image` commands** (exact, run after every rebuild):
```bash
kind load docker-image payguard-payment-api:latest --name payguard
kind load docker-image payguard-worker:latest --name payguard
```

**Namespace**: `payguard`, for every object in both the raw manifests and
the Helm chart.

**`imagePullPolicy`**:
| Workload | Value |
|---|---|
| `payment-api` | `Never` |
| `worker` | `Never` |
| `postgres` | `IfNotPresent` (default; pulled normally from Docker Hub) |
| `redis` | `IfNotPresent` (default; pulled normally from Docker Hub) |

**Container ports**:
| Workload | Port | Service? |
|---|---|---|
| `payment-api` | 8080 | Yes, `ClusterIP`, port 8080 → targetPort 8080 |
| `worker` | none | No Service |
| `postgres` | 5432 | Yes, `ClusterIP`, port 5432 → targetPort 5432 |
| `redis` | 6379 | Yes, `ClusterIP`, port 6379 → targetPort 6379 |

**Service DNS names** (standard Kubernetes cluster-DNS pattern
`<service>.<namespace>.svc.cluster.local`, short form `<service>` resolves
within the same namespace — mirrors Compose's service-name-as-hostname
behavior from Phase 3): `payment-api`, `postgres`, `redis` (Service object
names match the workload names, same as Compose's service keys).

**ConfigMap** (`payguard-config`, namespace `payguard`):
| Key | Value |
|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://postgres:5432/payments` |
| `REDIS_HOST` | `redis` |
| `REDIS_PORT` | `"6379"` |
| `POSTGRES_DB` | `payments` |

**Secret** (`payguard-secrets`, namespace `payguard`, `type: Opaque`):
| Key | Value |
|---|---|
| `DB_USERNAME` | `payguard` |
| `DB_PASSWORD` | `payguard` |
| `POSTGRES_USER` | `payguard` |
| `POSTGRES_PASSWORD` | `payguard` |

(`DB_USERNAME`/`POSTGRES_USER` and `DB_PASSWORD`/`POSTGRES_PASSWORD` are
intentionally duplicate keys with identical values, mirroring Phase 3's
Contracts note that Postgres's own container env and the app's connection
env vars "must match exactly" — kept as separate keys rather than one
key referenced twice, since `payment-api`/`worker` and the `postgres`
container consume different env var *names* for the same underlying
credential.)

**Env vars injected into `payment-api` and `worker` pods** (via
`envFrom`/`valueFrom` referencing the ConfigMap/Secret above — same set
Phase 3 already specified for Compose, now sourced from Kubernetes objects
instead of Compose's inline `environment:` block):
`SPRING_DATASOURCE_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`,
`REDIS_PORT`.

**Probes**:
| Workload | Readiness | Liveness |
|---|---|---|
| `postgres` | exec `pg_isready -U payguard -d payments`, `periodSeconds: 5` | Same command, `periodSeconds: 10`, `failureThreshold: 6` |
| `redis` | exec `redis-cli ping`, `periodSeconds: 5` | Same command, `periodSeconds: 10`, `failureThreshold: 6` |
| `payment-api` | httpGet `/` on port 8080, `initialDelaySeconds: 15`, `periodSeconds: 5` | Same, `initialDelaySeconds: 30`, `periodSeconds: 10`, `failureThreshold: 6` |
| `worker` | none | none |

**PersistentVolumeClaim** (`postgres-pvc`, namespace `payguard`):
| Field | Value |
|---|---|
| `storageClassName` | `standard` |
| `accessModes` | `[ReadWriteOnce]` |
| `resources.requests.storage` | `1Gi` |
| Mount path (in `postgres` Deployment) | `/var/lib/postgresql/data` |

**Resource requests/limits**: exact table in Decision section above.

**Helm chart identity**: chart name `payguard`, install command
`helm install payguard infra/helm/payguard -n payguard --create-namespace`.

## Manual Verification Mechanics

All commands below assume the `payguard` kind cluster already exists and
`kubectl`'s current context points at it (`kind create cluster --name
payguard` sets this automatically).

**Reach `payment-api` from the host:**
```bash
kubectl port-forward -n payguard svc/payment-api 8080:8080
# in another terminal:
curl http://localhost:8080/payments -X POST -H "Content-Type: application/json" -d '{...}'
```

**Confirm Postgres PVC persistence across a pod restart** (not just a
`kubectl apply` re-run — must actually kill the pod and let the Deployment
recreate it):
```bash
kubectl exec -n payguard deploy/postgres -- psql -U payguard -d payments -c "SELECT count(*) FROM payments;"
kubectl delete pod -n payguard -l app=postgres
kubectl wait -n payguard --for=condition=Ready pod -l app=postgres --timeout=60s
kubectl exec -n payguard deploy/postgres -- psql -U payguard -d payments -c "SELECT count(*) FROM payments;"
# same count both times => PVC survived the pod restart, not just the same container
```

**Confirm the worker pod self-heals on delete** (the Compose-vs-Kubernetes
contrast this phase's task list explicitly calls out — Compose's
`restart: no` from Phase 3 deliberately did *not* give this property):
```bash
kubectl get pods -n payguard -l app=worker
kubectl delete pod -n payguard -l app=worker
kubectl get pods -n payguard -l app=worker -w
# observe: a new pod with a new name appears automatically within seconds,
# without any `kubectl apply`/`create` command being run again — the
# Deployment's controller did this, not a human
```

## Open Questions

- [ ] `replicaCount` for `payment-api`/`worker` is set to `1` throughout this
      doc. Multiple `payment-api` replicas behind a single ClusterIP Service
      would work fine (stateless, Flyway migration is safe to run
      concurrently — Flyway uses a DB-level lock). Multiple `worker`
      replicas would also work today since Redis Streams consumer groups
      already give each message to exactly one consumer (Phase 1's design).
      Scaling either is plausible groundwork for Phase 7's autoscaling
      requirement (`PROJECT_PLAN.md`'s 2026-07-07 addition: "Autoscaling
      specifically only becomes meaningful once Phase 5 puts services on
      Kubernetes"). This doc deliberately keeps `replicaCount: 1` for both
      (simplest possible verification story for *this* phase) and leaves
      "should Phase 5 also prove `replicaCount: 2` works, as a preview of
      Phase 7's HPA work" as a question for the user/orchestrator rather
      than assuming either way.
- [ ] Whether to also add a `kubectl apply -k infra/k8s/` (Kustomize)
      pathway, since `CLAUDE.md`'s existing example command already shows
      `kubectl apply -k infra/k8s/` rather than `-f`. This doc specifies
      plain manifests applied via `-f` (or a directory glob); a bare
      `kustomization.yaml` listing the same files would make the existing
      `CLAUDE.md` example command work verbatim with near-zero extra effort
      (just a `resources:` list, no actual kustomization/patching used).
      Flagging as an open question rather than silently deciding, since it's
      a one-line addition either way and `CLAUDE.md` already documents `-k`
      usage without this doc having decided to introduce Kustomize.
- [ ] Whether Postgres/Redis image tags should be pinned to exact patch
      versions in the k8s manifests, mirroring the same open question Phase
      3's design doc left unresolved for Compose (`postgres:16-alpine` vs.
      `postgres:16.4-alpine`). Kept as a floating major tag here for
      consistency with the Compose file's current (still-unresolved) state
      — not re-litigating a decision Phase 3 already deferred, just noting
      it's still open.

## Out of Scope

- Any container registry (GHCR, Docker Hub, a local `registry:2` container)
  for `payment-api`/`worker` images — Phase 4 deliberately deferred registry
  push; `kind load docker-image` is this phase's complete answer for a
  local-only, no-registry workflow. A registry-backed, pull-based model is a
  natural fit once Phase 6 wants GitOps (Argo CD/Flux reconciling from a
  Git-declared image tag) — noted here as a forward pointer only, not
  reopened or decided now.
- `mock-downstream` and `chaos-injector` manifests — neither service exists
  in code yet.
- Ingress, `NodePort`, `LoadBalancer`, or any cluster-external access
  mechanism beyond `kubectl port-forward` — no external-access requirement
  exists yet; revisit if a later phase needs the system reachable without a
  developer's terminal open.
- HorizontalPodAutoscaler (HPA) — explicitly a Phase 7 concern per
  `PROJECT_PLAN.md`'s Phase 7 addition; this phase only lays the Deployment
  groundwork HPA would eventually target.
- Real secrets management (Vault, Sealed Secrets, External Secrets Operator,
  SOPS) — the Secret/ConfigMap split here is a modeling exercise only, per
  the Decision section's explicit "what a Secret does and does not protect
  against locally" discussion; still zero secrets-management infrastructure,
  consistent with Phase 3's identical Out of Scope item.
- Network policies, pod security standards/admission control, service mesh —
  no isolation requirement between these four workloads on a single-tenant
  local cluster; would be pure ceremony for this phase.
- Multi-node kind topology, node affinity/anti-affinity, pod topology
  spread constraints — no HA requirement, see Decision.
- Helm chart repository hosting/publishing (e.g. via a `gh-pages` branch or
  OCI registry) — the chart is installed locally from the filesystem path
  (`infra/helm/payguard`); publishing it anywhere is not a goal of this
  project.
- CI running against the kind cluster (e.g. a GitHub Actions job that spins
  up kind and runs `helm install` as a smoke test) — Phase 4's CI scope was
  explicitly lint/test/build-image only; extending CI to a live cluster is a
  bigger change not requested by `PHASE_5_TASKS.md`.
