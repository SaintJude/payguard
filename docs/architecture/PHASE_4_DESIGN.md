# Phase 4 Design — GitHub Actions CI for payment-api and worker

## Scope

This doc covers the design for `docs/phase-notes/PHASE_4_TASKS.md`: a GitHub
Actions workflow that runs on every pull request targeting `main` and every
push to `main`, running lint, unit tests, and a Docker image build for both
`payment-api` and `worker` — proving Phase 3's Dockerfiles still build — plus
a CI status badge on the README.

It specifies, concretely enough for a single implementer to build without
follow-up questions:
- Workflow file location, trigger config, job/step structure
- The lint/static-analysis tool choice for Java/Maven (none exists yet)
- Exact non-interactive Maven invocations for lint and test
- Maven dependency caching via `actions/setup-java`
- The Docker build step (reusing Phase 3's Dockerfiles verbatim)
- An explicit registry-push decision (yes/no) with reasoning
- The README badge markdown
- The required-status-check names Phase 2's branch protection would use,
  once someone opts to make them required (not done in this phase)

Out of scope: Kubernetes (Phase 5), GitOps/CD (Phase 6), chaos/observability
(Phase 7), Terraform (Phase 8), and `mock-downstream`/`chaos-injector` CI
(neither service exists in code yet). This doc does not re-litigate Phase 3's
Dockerfile contents (stage names, base images, build contexts) — it reuses
them as-is per `docs/architecture/PHASE_3_DESIGN.md`.

## Concept Primer

### What "CI" actually buys you here

Continuous Integration means: every time someone proposes a change (a PR) or
a change lands on the trunk branch (`main`), an automated pipeline builds and
tests it in a clean, disposable environment — not "it worked on my laptop."
For a solo learner this still matters: it catches "forgot to commit a file,"
"works locally because of leftover state," and "the Dockerfile silently
broke" — all real failure modes even with one contributor. Triggering on both
`pull_request` (to catch problems *before* merge) and `push` to `main` (to
catch problems that somehow land anyway, e.g. a squash-merge that combines
commits in a way nothing tested individually) covers both entry points into
the trunk.

### GitHub Actions building blocks

A **workflow** is one YAML file under `.github/workflows/`; GitHub watches
for the trigger events named in its `on:` block. A workflow contains one or
more **jobs**, which run in parallel by default on separate, fresh virtual
machines ("runners") — unless you say `needs: <other-job>`, which makes one
job wait for another to succeed first. Each job is a sequence of **steps**,
run in order on the same runner. A step is either `uses:` (run a reusable,
versioned "Action" someone published — e.g. `actions/checkout@v4`) or `run:`
(execute a shell command directly). Nothing persists between jobs unless you
explicitly pass it along (an artifact, a cache) — each job starts from a
blank VM.

### Matrix strategy

A **matrix** turns one job definition into N parallel job *instances*, one
per combination of values you list — here, one axis: `service: [payment-api,
worker]`. GitHub Actions then reports each instance as its own separate check
(e.g. `test (payment-api)`, `test (worker)`) in the PR UI, rather than one
job silently looping over both services and only reporting one pass/fail for
both. This is the difference that matters for branch protection later: a
matrix gives you per-service required checks; a hand-written loop inside one
job would only give you one combined check that can't tell you *which*
service broke.

By default a matrix has `fail-fast: true`, meaning the moment one leg fails,
GitHub cancels the other in-flight legs. For a two-service learning project
where the whole point is seeing both results clearly, that's the wrong
default — see Decision.

### "Lint" for a compiled, statically-typed language

In a language like Python or JavaScript, "lint" often means catching real
bugs (unused variables, undefined references) because the language itself
doesn't catch them at compile time. Java's compiler already catches most of
that class of error. What's left for a Java "lint" step is almost always
**code formatting/style consistency** — tools like Checkstyle (rule-based,
needs a rules XML file you maintain) or Spotless (delegates to an existing
formatter like `google-java-format` and just checks "is every file already
formatted per that standard," with zero rules file needed). Given this
project's "keep it minimal" agreement and that no linter exists yet, the
lowest-ceremony real static check is preferred over building out a
Checkstyle rule set from scratch — see Decision and Alternatives.

### Dependency caching

Every `mvn test` or `mvn package` needs the project's full dependency tree
(Spring Boot starters, Flyway, Lombok, transitively dozens of jars) present
in the local repository (`~/.m2/repository`). A GitHub-hosted runner is a
brand-new VM every single run — nothing is pre-downloaded. Without caching,
every CI run re-downloads the entire dependency tree from Maven Central,
which is slow (adds real minutes per run) and, at scale, is the kind of
thing that gets a project rate-limited by Maven Central. `actions/setup-java`
has a built-in `cache: maven` option: it transparently saves `~/.m2/repository`
to GitHub's cache storage after a run and restores it on the next one, keyed
off a hash of your `pom.xml` file(s) — so cache is invalidated automatically
exactly when dependencies actually change, and reused otherwise.

### Docker builds in CI vs. locally

This is the *same* Dockerfile you already run with `docker compose up
--build` locally — GitHub-hosted Ubuntu runners have Docker pre-installed, so
`docker build -t <tag> <context>` just works as a plain shell step, no new
tooling to learn. Running it in CI proves something a local build can't: that
the Dockerfile builds correctly from a clean checkout with no leftover local
`target/` directories, cached layers, or "works on my machine" state — the
same guarantee Phase 3's `.dockerignore` files were designed to protect
against locally.

### Container registries, and what "publishing" even means

A Docker image built on a CI runner is destroyed the moment the job ends
unless you explicitly push it somewhere. A **registry** (Docker Hub, GHCR,
etc.) is just a server that stores tagged images so something else — another
machine, another CI job, a Kubernetes cluster — can `docker pull` them later.
Nothing in PayGuard today pulls a published image: Compose builds locally,
and there's no cluster yet. Publishing to a registry before something
actually consumes the published artifact is a form of building ahead of
need — worth naming explicitly given this project's anti-over-engineering
agreement. See Decision for the concrete call.

### GHCR / GitHub Actions and the "no cloud provider" rule — a judgment call

`CLAUDE.md` says no AWS/GCP/Azure dependencies — everything runs locally.
GitHub Actions (the CI runner) and GHCR (GitHub's container registry) are
technically third-party hosted infrastructure, not "your laptop." This doc
treats them as *not* falling under the cloud-provider rule's intent, on the
reasoning that the rule is about avoiding vendor lock-in to a cloud compute
provider for the *application's runtime* (AWS RDS, a GKE cluster, etc.) —
whereas GitHub is already the git host this project depends on for Phase 2's
branch protection, PRs, and Actions is the standard place to run CI for a
GitHub-hosted repo. That said, this is an interpretation, not something
explicitly settled in `CLAUDE.md` — flagged here rather than assumed
silently, and captured under Open Questions in case the user disagrees.

### Status badges

A badge is just an `<img>`/Markdown image pointing at a GitHub-generated SVG
URL for a specific workflow file. GitHub renders it based on the **most
recent completed run of that workflow on the workflow's default query**,
which — unless you add a `?branch=` query param — means the latest run
associated with the repository's default branch (`main`), not the latest PR
run. That's a common point of confusion: a badge can show green from the
last successful push to `main` even while an open PR's CI is currently red;
this is expected behavior, not a bug to fix.

### How this interacts with Phase 2's branch protection

Phase 2 set up branch protection requiring PRs into `main` (no direct
pushes, even for the repo owner). Branch protection has a separate,
optional setting: **required status checks** — a list of named CI checks
that must report success before GitHub allows a PR to merge, even one that
otherwise satisfies all other rules. GitHub only lets you *pick* a check
name from ones that have reported at least once — so the checks need to run
at least once (e.g., via this phase's throwaway PR) before an admin can even
see them in the dropdown. This phase does not turn that setting on (per
`PHASE_4_TASKS.md`); this doc just names what the checks would be called so
that step is a two-minute settings change later, not a research task.

## Decision

### One workflow file, matrix over service

**File**: `.github/workflows/ci.yml` (single file, not split per service).

**Reasoning**: one file means one place to see/maintain the trigger config,
and a matrix (`service: [payment-api, worker]`) scales cleanly if
`mock-downstream` arrives in a later phase — add one array element, not a
whole new file with a duplicated `on:` block. See Alternatives for the
per-service-file option and why it's more ceremony for no real benefit at
this scale.

### Trigger

```yaml
on:
  pull_request:
    branches: [main]
  push:
    branches: [main]
```

Exactly these two events, both scoped to `main` (this repo's only long-lived
branch per Phase 2). No `workflow_dispatch` or path filters added — not
asked for, and path-filtering two small Maven modules is premature
optimization for a project this size.

### Job structure: two jobs, `test` then `docker-build`, each matrixed over service

```yaml
jobs:
  test:
    name: test
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        service: [payment-api, worker]
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven

      - name: Lint (Spotless)
        run: mvn -B spotless:check -f services/${{ matrix.service }}/pom.xml

      - name: Unit tests
        run: mvn -B test -f services/${{ matrix.service }}/pom.xml

  docker-build:
    name: docker-build
    runs-on: ubuntu-latest
    needs: test
    strategy:
      fail-fast: false
      matrix:
        service: [payment-api, worker]
    steps:
      - uses: actions/checkout@v4

      - name: Build image
        run: docker build -t payguard/${{ matrix.service }}:ci ./services/${{ matrix.service }}
```

(This is an illustrative snippet for the implementer to build from — this
doc does not create `.github/workflows/ci.yml`.)

**Why two jobs, `docker-build` gated behind `test` (not parallel)**: the
point of this phase is a pipeline that *gates* on quality, not just runs
things concurrently for speed. `needs: test` means GitHub won't spend
runner-minutes building a Docker image for code that doesn't even pass its
own unit tests. For a two-service project the extra ~1-2 minutes of
sequential runtime is a worthwhile trade for that guarantee, and it's the
more standard/teachable CI shape (lint → test → build). See Alternatives for
the parallel option.

**Why lint and test are steps within one `test` job, not a third matrixed
job**: a matrix already multiplies job count by the number of services (2).
Adding a separate `lint` job would make it 2 (lint) + 2 (test) + 2
(docker-build) = 6 job instances for two services — a lot of ceremony to
watch in the Actions UI for a learning project. Keeping lint as an explicit,
separately-named *step* inside `test` still gives full visibility (a failed
lint step shows up distinctly in the job's log and fails the job before
`mvn test` even runs) without doubling the job count.

**Why `fail-fast: false` on both matrices**: the default (`true`) cancels
the `worker` leg the instant `payment-api` fails, so you never learn worker's
lint/test status in that CI run. For a two-service project the very small
extra runner time from letting both finish is worth always seeing both
results. See Alternatives.

### Lint tool: Spotless with `google-java-format`

**Decision**: add the `spotless-maven-plugin` to each service's `pom.xml`,
configured with `google-java-format`, and run `mvn spotless:check` as an
explicit CI step (not bound into the `test`/`verify` lifecycle phase, so it
shows as its own clearly-labeled step in the Actions log).

Illustrative `pom.xml` addition (for the implementer — this doc does not
edit `pom.xml`):

```xml
<plugin>
  <groupId>com.diffplug.spotless</groupId>
  <artifactId>spotless-maven-plugin</artifactId>
  <version>2.43.0</version> <!-- implementer: confirm latest stable at build time -->
  <configuration>
    <java>
      <googleJavaFormat/>
    </java>
  </configuration>
</plugin>
```

**Reasoning**: Spotless needs zero rules file — it just checks "is this file
byte-identical to what `google-java-format` would produce," and
`spotless:apply` auto-fixes any violation locally in one command. Checkstyle
is the more "enterprise" choice (configurable rule severity, more categories
of check) but requires authoring and maintaining a rules XML from scratch for
a project that has no existing style conventions to encode — pure ceremony
for a two-service learning project. Maven's own `validate` phase (`mvn
validate`) does POM/dependency sanity checks, not code style — it's not
really a lint step at all, just early-fail plumbing that already happens
implicitly.

### Maven invocations

Exact, non-interactive commands (matrix substitutes `matrix.service`):

```
mvn -B spotless:check -f services/${{ matrix.service }}/pom.xml
mvn -B test -f services/${{ matrix.service }}/pom.xml
```

`-B` (batch mode) suppresses interactive prompts/ANSI color codes not needed
in CI logs — standard practice for any non-interactive Maven invocation.
`-f` targets each module's `pom.xml` directly, mirroring the existing
`Makefile`'s `mvn -f services/<x>/pom.xml test` pattern for consistency with
what's already established. No `JAVA_HOME` pinning hack is needed (unlike
Phase 1's Homebrew workaround) — `actions/setup-java@v4` with
`java-version: '21'` installs and points at an exact, versioned JDK 21
directly.

### Caching

`actions/setup-java@v4`'s `cache: maven` input, as shown in the job snippet
above. This wraps `actions/cache` internally, keyed by a hash of all
`pom.xml` files found in the checkout. One caveat worth knowing: because
both matrix legs check out the *entire* repo (both services' `pom.xml`
files are present regardless of which `matrix.service` value that job
instance is building), the cache key is identical across both legs of the
matrix — not scoped per-service. This is harmless (both legs' dependency
sets overlap heavily anyway, e.g. both use `spring-boot-starter-data-redis`)
but means you may see a "cache already exists, skipping save" note from the
second leg to finish — expected, not an error.

### Docker build step

Plain `docker build -t payguard/<service>:ci ./services/<service>` per
service, run as a shell step (no third-party build action). This reuses
Phase 3's Dockerfiles and build contexts exactly as documented in
`docs/architecture/PHASE_3_DESIGN.md` (context = each service's own
directory, stage names `build`/`runtime` — no changes needed here). No
`docker/setup-buildx-action` or `docker/build-push-action` is introduced —
those add Buildx (multi-platform builds, registry-cache-aware layer
caching) that this project has no current use for, since nothing is pushed
or built for multiple architectures. See Alternatives.

The image tags (`payguard/payment-api:ci`, `payguard/worker:ci`) are
**local to the runner and never pushed** — they exist only to prove the
`docker build` command exits 0, and are discarded when the runner VM is torn
down at the end of the job.

### Registry push: **No**, not in this phase

**Decision**: do not push images to GHCR (or any registry) in Phase 4.
Building without pushing already proves the thing this phase's Definition of
Done asks for — "build both images (proving Phase 3's Dockerfiles still
build)" — because `docker build` fails loudly (non-zero exit, red CI) if the
Dockerfile, base images, or build context are broken, exactly as it would
locally. A registry push doesn't add proof of buildability; it adds proof of
*publishability*, which nothing downstream needs yet: Compose builds locally
(Phase 3), and no cluster exists to `docker pull` a published image until
Phase 5 at the earliest. Adding it now means introducing registry auth,
tagging/versioning scheme, and image retention/cleanup concepts before
anything consumes the result — the "don't over-engineer" agreement points
squarely at deferring this.

**If/when this is turned on** (documented here so it's a five-minute change
later, not a redesign): GHCR image naming would be
`ghcr.io/saintjude/payguard-payment-api` and
`ghcr.io/saintjude/payguard-worker` (GHCR namespaces images under the
GitHub org/user, and hyphenating the service into the image name avoids
needing multiple GHCR "packages" configuration steps). Tag scheme: the Git
SHA (`${{ github.sha }}`) for every build, plus `:latest` only on pushes to
`main` (not on PR builds, to avoid a PR's unmerged code ever being tagged
`latest`). Auth: `docker/login-action@v3` against `ghcr.io` using
`${{ github.actor }}` / `${{ secrets.GITHUB_TOKEN }}` — the token GitHub
auto-provisions for every workflow run, scoped to this repo; no new secret
needs to be created or rotated by hand.

### README badge

Exact markdown to add near the top of `README.md` (implementer's job, not
this doc's):

```markdown
[![CI](https://github.com/SaintJude/payguard/actions/workflows/ci.yml/badge.svg)](https://github.com/SaintJude/payguard/actions/workflows/ci.yml)
```

The badge reflects the status of the **most recent completed run of
`ci.yml` on `main`** (GitHub's default query when no `?branch=` param is
given) — it does not reflect the status of an in-flight or completed PR run
against a feature branch. It will show "no status"/unrendered until the
workflow has run at least once after being merged to `main`.

### Required status checks (named now, not enabled this phase)

Once an admin opts into required status checks on the branch protection rule
(Phase 2's rule, not touched in this phase), the checks available to pick
from would be:
- `test (payment-api)`
- `test (worker)`
- `docker-build (payment-api)`
- `docker-build (worker)`

These names come directly from the job's `name:` field combined with the
matrix value GitHub Actions appends automatically. They only become
selectable in GitHub's branch-protection UI after the workflow has run at
least once (per the Concept Primer note above) — the manual-verification
task's throwaway PR satisfies that.

## Alternatives Considered & Tradeoffs

| Option | Pros | Cons | Why not chosen |
|---|---|---|---|
| Split into `ci-payment-api.yml` + `ci-worker.yml` instead of one matrixed `ci.yml` | Each service's pipeline can diverge independently without touching a shared file | Duplicated `on:` trigger blocks; adding a third service (`mock-downstream`, later phase) means a whole new file instead of one matrix array entry | One file + matrix is less to maintain and scales better as services are added |
| `docker-build` runs in parallel with `test` (no `needs:`) | Faster wall-clock CI (~1-2 min saved); still catches build breakage | Spends runner-minutes building a Docker image for code whose tests haven't been proven to pass yet; less didactic — doesn't demonstrate a "gate" | This phase is explicitly about teaching a pipeline that gates on quality, not just parallelizing for speed |
| `fail-fast: true` (matrix default) | Slightly faster feedback loop when something's badly broken; saves runner minutes on the cancelled leg | Cancels the sibling service's job mid-run, so a `payment-api` failure hides whether `worker` also has a problem in the same CI run | Full visibility into both services' status matters more than a few saved minutes for a 2-service project |
| Checkstyle instead of Spotless for lint | More granular, configurable rule categories (unused imports, cyclomatic complexity, etc.); more "industry standard" at large companies | Requires authoring and maintaining a rules XML from scratch (no existing convention to encode); more setup ceremony for a project with two small services | Spotless's zero-config "is this formatted correctly" check is the minimal real static check; revisit if the codebase grows enough to want deeper rules |
| `mvn validate` only (no real lint tool) | Zero new dependencies; already implicitly runs before `test` | Doesn't actually check code style/formatting — it's dependency/POM sanity checking, not a linter; wouldn't satisfy "choose and wire up a lint step" from the task list in good faith | Doesn't meet the actual goal of having a lint step; picking it would be lint-in-name-only |
| `docker/build-push-action` (+ `docker/setup-buildx-action`) instead of plain `docker build` shell step | Registry-cache-aware layer caching across runs; multi-platform build support if ever needed; the standard action if push is turned on later | Introduces Buildx and two more marketplace actions to understand for a benefit (advanced caching, multi-arch) this project doesn't need yet, since nothing is pushed | Plain `docker build` is one command, needs no new concepts, and fully proves buildability; revisit if/when registry push is turned on (Buildx's cache becomes valuable once you're pushing on every run) |
| Push to GHCR now | "Complete" CI/CD feel; images ready to pull once Phase 5's kind cluster exists | Nothing consumes a published image yet; adds registry-auth and tag-versioning concepts ahead of any actual need, against the "don't over-engineer" agreement | Explicitly optional per `PROJECT_PLAN.md`; defer until Phase 5 gives a real consumer |
| GHCR/Actions treated as violating the "no cloud provider" rule (avoid using either) | Strictest possible reading of `CLAUDE.md`'s rule, zero ambiguity | No CI is possible at all without *some* execution environment beyond a laptop; GitHub is already the git host this project depends on since Phase 2 | Treated as intent-based (avoid third-party *cloud compute/runtime* dependencies for the app itself, not the git platform already in use) — flagged explicitly as a judgment call rather than assumed silently; see Open Questions |

## Contracts

**File this design specifies** (created by the implementer, not this doc):
`.github/workflows/ci.yml`.

**Trigger** (`on:` block): `pull_request` targeting `main`; `push` to `main`.

**Job names** (exact, matter for branch-protection required-check selection):
`test`, `docker-build`. Combined with matrix values, GitHub reports them as
`test (payment-api)`, `test (worker)`, `docker-build (payment-api)`,
`docker-build (worker)`.

**Matrix values**: `service: [payment-api, worker]`, `fail-fast: false`, on
both jobs.

**JDK setup**: `actions/setup-java@v4`, `distribution: temurin`,
`java-version: '21'`, `cache: maven`.

**Lint dependency to be added** (implementer edits `pom.xml`, not this doc):
`com.diffplug.spotless:spotless-maven-plugin`, configured with
`<googleJavaFormat/>`, in both `services/payment-api/pom.xml` and
`services/worker/pom.xml`.

**Exact commands** (per matrix leg):
```
mvn -B spotless:check -f services/${{ matrix.service }}/pom.xml
mvn -B test -f services/${{ matrix.service }}/pom.xml
docker build -t payguard/${{ matrix.service }}:ci ./services/${{ matrix.service }}
```

**Docker build context**: `./services/payment-api` and `./services/worker`
respectively — identical to Phase 3's `docker-compose.yml` `build.context`
values (`docs/architecture/PHASE_3_DESIGN.md`). No Dockerfile changes.

**Registry push**: none this phase. `GITHUB_TOKEN` (auto-provisioned,
no new secret) is the auth mechanism named here for if/when push is turned
on later — no other secret should be created for this phase.

**README badge markdown** (added by implementer to `README.md`):
```markdown
[![CI](https://github.com/SaintJude/payguard/actions/workflows/ci.yml/badge.svg)](https://github.com/SaintJude/payguard/actions/workflows/ci.yml)
```

**Repo coordinates used above**: `SaintJude/payguard` (from `git remote -v`
— origin `https://github.com/SaintJude/payguard.git`). If the repo is ever
renamed or transferred, the badge URL and any future GHCR image path must be
updated to match.

**Future required status check names** (not enabled this phase): `test
(payment-api)`, `test (worker)`, `docker-build (payment-api)`, `docker-build
(worker)`.

## Open Questions

- [ ] Confirm the interpretation that GHCR and GitHub Actions themselves do
      not count as "cloud provider dependencies" under `CLAUDE.md`'s rule
      (they're treated here as the git-hosting platform already in use, not
      a third-party cloud runtime for the application) — this doc proceeds
      on that reading, but it's a judgment call the user should explicitly
      bless or override.
- [ ] Confirm the "no registry push this phase" recommendation, or say now
      if the preference is to turn on GHCR push immediately for the
      practice rep (the exact scheme is already specified above under
      Decision/Contracts if so — turning it on would just mean adding the
      `docker/login-action` + `push: true` steps, no redesign needed).

## Out of Scope

- Kubernetes manifests, Helm charts, or anything that *consumes* a published
  image (Phase 5).
- GitOps/CD tooling (Argo CD/Flux, Phase 6).
- `mock-downstream` and `chaos-injector` CI — neither service exists in code
  yet.
- OpenTelemetry/Prometheus/Grafana in CI (Phase 7 — unrelated to this phase).
- Terraform-managed CI infrastructure (Phase 8).
- Enabling required status checks on the Phase 2 branch protection rule —
  this doc only names what they'd be called; turning the setting on is
  explicitly deferred per `PHASE_4_TASKS.md`.
- Multi-architecture Docker builds (`linux/amd64` + `linux/arm64`) in CI —
  same reasoning as Phase 3: no distribution target that needs it.
- Dependabot/Renovate or any automated dependency-update workflow — not
  asked for in this phase's task list.
