# Phase 3 Design — Containerizing payment-api and worker

## Scope

This doc covers the design for `docs/phase-notes/PHASE_3_TASKS.md`: a
multi-stage `Dockerfile` for `payment-api`, a multi-stage `Dockerfile` for
`worker`, and a root `docker-compose.yml` wiring both services together with
Postgres and Redis, replacing the Homebrew-services-on-the-host model from
Phases 1–2.

It specifies, concretely enough for two implementers who won't talk to each
other to build matching artifacts:
- Base images for build and runtime stages, and why
- Exact Dockerfile structure (stage names, working dirs, COPY sources/targets)
- Exposed ports
- How each container learns its Postgres/Redis connection info
- Compose startup ordering (`depends_on` + healthchecks) so the worker never
  starts before `payment-api`'s Flyway migration has created `payments`
- `docker-compose.yml` shape: service names, volumes, networking
- `.dockerignore` contents (implementers create these; not application code)

Out of scope: Kubernetes (Phase 5), CI image builds/publishing (Phase 4),
`mock-downstream`/`chaos-injector` (not built yet — no Dockerfile needed),
observability sidecars (Phase 7), and Terraform (Phase 8).

This doc does not re-litigate anything from `docs/architecture/PHASE_1_DESIGN.md`
(Redis Streams contract, schema ownership, retry semantics) — those are
settled and the design below is built to be consistent with them.

## Concept Primer

### Multi-stage Docker builds

A naive Dockerfile that runs `mvn package` directly in your final image
bakes in the entire JDK, the full Maven distribution, every dependency `.jar`
Maven downloaded to build the thing, and the source tree — none of which are
needed to *run* a Spring Boot app, only to *build* one. That image is
needlessly large and has a bigger attack surface (a JDK includes a
compiler; a JRE doesn't).

A multi-stage build solves this by using more than one `FROM` in a single
Dockerfile. Each `FROM` starts a fresh, independent stage with its own
filesystem. Later stages can selectively pull files out of earlier stages
with `COPY --from=<stage-name>`, discarding everything else. So: stage one
("build") has the JDK and Maven and compiles a `.jar`; stage two ("runtime")
starts from a much smaller JRE-only base image and copies in *only* that one
`.jar`. The final image never contains a compiler, Maven's dependency cache,
or your `.java` source files — just the JVM and the artifact it runs.

This also gets you Docker's **layer caching** for free if you order steps
right: copying `pom.xml` alone and running `mvn dependency:go-offline` before
copying `src/` means Docker only re-downloads dependencies when `pom.xml`
changes, not on every source edit.

### Base image choice: JDK vs JRE, and which distro

A **JDK** image includes the compiler (`javac`) and dev tooling; a **JRE**
image includes only what's needed to *run* already-compiled bytecode. The
build stage needs a JDK (Maven invokes `javac`); the runtime stage only ever
runs `java -jar app.jar`, so a JRE is sufficient and meaningfully smaller.

Distro matters too. `eclipse-temurin` (the community-maintained continuation
of AdoptOpenJDK builds) ships both Ubuntu/Debian-based (`-jammy`) and
Alpine-based (`-alpine`) variants. Alpine images are built on `musl` libc
instead of `glibc` and use BusyBox for core utilities — much smaller, and
since a Spring Boot app is pure JVM bytecode with no native/glibc
dependencies, Alpine works fine here. The one practical wrinkle: Alpine's
BusyBox `wget` is what we'll lean on for container healthchecks (no `curl` by
default) — covered in Contracts below.

Phase 1 hit a real bug from *not* pinning a JDK version precisely — Homebrew's
unversioned `openjdk` formula tracked JDK 26, and Lombok's annotation
processor didn't support it yet, silently breaking builds. Docker images are
versioned explicitly (`eclipse-temurin:21-...`), so this class of bug can't
recur here as long as we pin the major version PayGuard has already
standardized on (21, per `CLAUDE.md` and both `pom.xml`s' `<java.version>`).

### Where Spring Boot gets its config: `application.yml` defaults vs environment variables vs profiles

Both services' `application.yml` files already contain lines like:
```yaml
datasource:
  url: jdbc:postgresql://localhost:5432/payments
data:
  redis:
    host: ${REDIS_HOST:localhost}
```
The `${REDIS_HOST:localhost}` syntax is a **placeholder with a default** —
Spring resolves it from an environment variable named `REDIS_HOST` if one
exists, falling back to `localhost` otherwise. The `datasource.url` line has
no such placeholder; it's a literal value. That does *not* mean it's
unoverridable, though: Spring Boot's configuration system has a fixed
precedence order, and OS environment variables always outrank values loaded
from `application.yml`, regardless of whether the `.yml` value itself
contains a placeholder. An environment variable named `SPRING_DATASOURCE_URL`
(Spring's "relaxed binding" maps `SPRING_DATASOURCE_URL` → the property
`spring.datasource.url`) will override the hardcoded value in the file
entirely.

This means containers can get correct connection info purely through
environment variables at `docker run`/compose time, with **zero changes to
either `application.yml`** — the alternative (a separate
`application-docker.yml` activated by `SPRING_PROFILES_ACTIVE=docker`) is a
second, parallel way to achieve the same thing, discussed under Alternatives.

### Compose healthchecks and `depends_on` conditions

Plain `depends_on: [postgres]` in Compose only waits for the *container* to
start — not for Postgres to actually be ready to accept connections, which
can take a few seconds after the process launches. A **healthcheck** is a
command Docker runs *inside* the container on an interval to decide if it's
"healthy"; `depends_on` can then use `condition: service_healthy` to block a
dependent service from starting until its dependency reports healthy, not
just "started."

This matters here for a reason specific to this system: `payment-api` runs
Flyway migrations during Spring's `ApplicationContext` startup, which
completes *before* the embedded web server starts accepting HTTP connections.
That means "the HTTP port is accepting requests" is a reliable proxy for "the
Flyway migration already ran and `payments` exists" — we can use an HTTP
healthcheck on `payment-api` itself as the signal that gates `worker`'s
startup, without needing a dedicated migration-status endpoint.

### Named volumes

A container's filesystem is ephemeral by default — `docker compose down`
followed by `up` gives Postgres a brand-new, empty filesystem, wiping all
committed payment rows. A **named volume** is storage managed by Docker
outside any single container's lifecycle; mounting it at Postgres's data
directory means the data outlives `down`/`up` cycles (it's only destroyed by
an explicit `docker compose down -v` or `docker volume rm`).

## Decision

### Dockerfiles — shared pattern for both services

**Build stage base image**: `maven:3.9-eclipse-temurin-21` — an official
Maven image built on top of `eclipse-temurin:21-jdk`, so it has both Maven
and JDK 21 preinstalled (no separate Maven install step needed).

**Runtime stage base image**: `eclipse-temurin:21-jre-alpine` — JRE only
(no compiler), Alpine-based for a small footprint. BusyBox `wget` (present by
default on Alpine) is used for the `payment-api` healthcheck; no extra
package install needed.

**Stage names**: `build` and `runtime` (exact strings — required so
`COPY --from=build` resolves identically in both Dockerfiles).

**Working directories**: `/build` in the build stage, `/app` in the runtime
stage.

**Build context**: each Dockerfile's build context is its own service
directory (`services/payment-api/` or `services/worker/`), matching the
existing repo layout where each service is a self-contained Maven module with
no shared parent aggregator POM (per `PHASE_1_DESIGN.md`'s alternatives
table). Compose's `build.context` for each service points at that directory.

**JAR copy**: use a wildcard (`/build/target/*.jar`) rather than the
hardcoded versioned name (e.g. `payment-api-0.1.0-SNAPSHOT.jar`), and rename
the result to a fixed `app.jar` in the runtime stage. This decouples the
Dockerfile from `pom.xml`'s `<version>` — a version bump (routine in any real
project) then requires no Dockerfile edit. There is exactly one jar in
`target/` after `package -DskipTests` (no separate `-sources.jar` etc. is
produced by this build), so the wildcard is unambiguous.

**Illustrative Dockerfile** (`services/payment-api/Dockerfile` — `worker`'s
is identical except it omits the `EXPOSE` line; see Contracts for the exact
diff):

```dockerfile
# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S payguard && adduser -S payguard -G payguard
COPY --from=build /build/target/*.jar app.jar
USER payguard
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Running as a non-root user (`payguard`, created via Alpine's `addgroup`/
`adduser`) costs nothing here and is worth the habit even in a learning
project — running containers as root by default is one of the most common
real-world container-security footguns.

**Worker's Dockerfile**: identical structure, base images, stage names, and
working directories, with two differences: no `EXPOSE` line (worker has no
HTTP surface — see Contracts) and no healthcheck (nothing in this system
depends on worker's own readiness).

### Configuration: environment variables, not Spring profiles

Both services get their Postgres/Redis connection info purely via
environment variables set in `docker-compose.yml` — no new
`application-docker.yml`, no `SPRING_PROFILES_ACTIVE`. See Concept Primer for
why this works with zero `application.yml` changes. This is the smaller,
more minimal change consistent with the "keep services minimal" working
agreement — see Alternatives for the profile-based option and why it's
overkill here.

### Compose startup sequencing

1. `postgres` and `redis` each get a healthcheck (`pg_isready` for Postgres,
   `redis-cli ping` for Redis — both binaries ship in their official images).
2. `payment-api` has `depends_on: postgres: condition: service_healthy` and
   same for `redis`. It also defines its **own** healthcheck: a `wget`
   spider-request against `http://localhost:8080/` (the static test-portal
   page from Phase 1, served once the embedded web server is up — which, per
   the Concept Primer, only happens after Flyway's migration has already
   run).
3. `worker` has `depends_on: payment-api: condition: service_healthy` (plus
   `redis: condition: service_healthy` — worker talks to Redis directly too).
   Because `payment-api` isn't reported healthy until *after* its Flyway
   migration completed and its port is accepting connections, by the time
   `worker` starts, `payments` is guaranteed to exist — satisfying Phase 1's
   constraint that worker never runs migrations and depends on the table
   already being there.

No restart policy (`restart:`) is set on any service — left at Compose's
default of `no`. This is deliberate, not an oversight: Phase 1 already hit
one nasty bug (the worker's daemon-thread JVM-exit issue) where the process
silently died. An automatic restart policy would make a *recurrence* of that
class of bug (or any future crash) harder to notice — the container would
just quietly relaunch instead of visibly stopping. For a learning project
where "notice failures" is the point, a service that's supposed to be
running but isn't should stay stopped and visible in `docker compose ps`.

### docker-compose.yml shape

- **Services**: `payment-api`, `worker`, `postgres`, `redis` — these names
  double as the DNS hostnames containers use to reach each other on
  Compose's default network (no custom `networks:` block needed — the
  automatically created default bridge network is sufficient for four
  services with no isolation requirements between them).
- **`postgres`**: image `postgres:16-alpine`; env `POSTGRES_DB=payments`,
  `POSTGRES_USER=payguard`, `POSTGRES_PASSWORD=payguard`; named volume
  `pg-data` mounted at `/var/lib/postgresql/data`; port mapping
  `5432:5432` (optional — see note below).
- **`redis`**: image `redis:7-alpine`; no volume (queue contents don't need
  to survive a `down`/`up` cycle for this project — Redis Streams data loss
  on full teardown is an accepted, unaddressed gap, same tier as the
  documented payment-api dual-write gap from Phase 1); port mapping
  `6379:6379` (optional, same note).
- **`payment-api`**: `build: context: ./services/payment-api`; port mapping
  `8080:8080`; environment as specified in Contracts below; `depends_on` as
  specified above.
- **`worker`**: `build: context: ./services/worker`; no port mapping (no
  HTTP surface); environment as specified in Contracts; `depends_on` as
  specified above.
- No top-level `version:` key — the modern Compose Specification (used by
  current `docker compose` CLI, as opposed to the older standalone
  `docker-compose` tool) doesn't require or want one; including it just
  produces a harmless-but-noisy deprecation warning.

**Note on the Postgres/Redis host port mappings**: these exist purely for
optional local debugging (`psql`/`redis-cli` from the host, outside any
container). They are *not* required for `payment-api`/`worker` to reach
`postgres`/`redis` — container-to-container traffic happens over the
Compose-internal network regardless of host port mapping. If Homebrew's
Postgres/Redis are still running when you `docker compose up`, these
mappings will fail with a "port already in use" error — stop the Homebrew
services first, or remove the mappings if you don't need host-side access.

## Alternatives Considered & Tradeoffs

| Option | Pros | Cons | Why not chosen |
|---|---|---|---|
| Runtime base: `eclipse-temurin:21-jre-jammy` (Ubuntu/glibc) instead of `-jre-alpine` | Familiar `apt`-based tooling; `curl` available by default; glibc avoids the (rare, mostly-native-lib-related) class of musl compatibility issues | Meaningfully larger image; Spring Boot has no native/glibc dependency here, so the compatibility benefit is moot for this project | Alpine is smaller with no real downside for a pure-JVM app; BusyBox `wget` covers the one healthcheck we need |
| Build stage: `eclipse-temurin:21-jdk-jammy` + manually install Maven instead of `maven:3.9-eclipse-temurin-21` | One fewer image maintainer to trust; slightly more control over Maven version | Extra `RUN` steps to download/verify/install Maven; more Dockerfile boilerplate for no real benefit | The official Maven image already bundles the exact JDK 21 we want with zero extra setup |
| Google's "distroless" Java runtime image instead of `eclipse-temurin:21-jre-alpine` | Even smaller; no shell at all (smaller attack surface) | No shell means no way to run a `wget`/`curl`-based healthcheck at all, and no way to `docker exec` in for debugging — a real cost on a project whose explicit purpose is hands-on learning | Alpine's small extra surface area is worth it for healthcheck capability and debuggability |
| Hardcode the versioned jar name in `COPY --from=build` instead of a wildcard | Explicit, no ambiguity about which file is copied if `target/` ever contains multiple jars | Couples the Dockerfile to `pom.xml`'s `<version>`; every version bump requires a Dockerfile edit in lockstep | Wildcard is standard practice for this exact reason and there's no scenario in this build that produces multiple jars |
| Spring profile (`application-docker.yml` + `SPRING_PROFILES_ACTIVE=docker`) instead of plain environment variables | Keeps all docker-specific config in one file, visible in the repo instead of scattered across `docker-compose.yml`'s `environment:` blocks; arguably clearer for a much larger config surface | Adds a new file, a new activation mechanism, and a second place config can live (file vs env var) to reason about — for two env vars per service, that's more ceremony than value | `application.yml` already has `REDIS_HOST`/`REDIS_PORT` placeholders and Spring's env-var precedence already covers `datasource.url` for free; no new files needed |
| `depends_on: condition: service_healthy` on `payment-api` (HTTP healthcheck) instead of a `sleep`/retry-loop wrapper script in `worker`'s entrypoint | Uses Compose's built-in mechanism; no custom shell script to maintain; declarative | Requires payment-api to have *some* HTTP endpoint to poll (works here because Phase 1's static test portal already exists at `/`; would need `spring-boot-starter-actuator` + `/actuator/health` if it didn't) | Compose's native mechanism is simpler and the static portal already gives us a free healthcheck target |
| `restart: unless-stopped` on all services | Stack self-heals from transient crashes without manual intervention; more "production-like" | Would mask a recurrence of Phase 1's silent-JVM-exit bug (or any future crash) by auto-relaunching instead of leaving visible evidence in `docker compose ps` | This project's explicit goal is to notice and diagnose failures, not paper over them; revisit once Phase 7 wants deliberately-crashing chaos scenarios with recovery semantics |

## Contracts

**Files this design specifies** (to be created by the two Dockerfile
implementers and the compose follow-up step — none created by this doc):
- `services/payment-api/Dockerfile`
- `services/payment-api/.dockerignore`
- `services/worker/Dockerfile`
- `services/worker/.dockerignore`
- `docker-compose.yml` (repo root)

**Dockerfile stage names** (must match exactly, both services):
`build`, `runtime`.

**Dockerfile working directories**: `/build` (build stage), `/app` (runtime
stage), both services.

**Base images** (both services):
| Stage | Image |
|---|---|
| build | `maven:3.9-eclipse-temurin-21` |
| runtime | `eclipse-temurin:21-jre-alpine` |

**Runtime entrypoint** (both services): `ENTRYPOINT ["java", "-jar", "app.jar"]`,
where `app.jar` is `COPY --from=build /build/target/*.jar app.jar` renamed
into `/app/app.jar`.

**Exposed ports**:
| Service | `EXPOSE` in Dockerfile | Reason |
|---|---|---|
| `payment-api` | `8080` | Has an HTTP surface (`server.port: 8080` in its `application.yml`) |
| `worker` | none | No HTTP surface — Redis Streams consumer + DB writer only |

**`.dockerignore` contents** (same pattern, both services — implementers
create the file, this specifies its contents):
```
target/
*.log
.run/
.git/
.gitignore
Dockerfile
.dockerignore
```
(`target/` is the important one — without it, the build context upload
includes any locally-built classes/jars, which are irrelevant since the build
stage recompiles from source anyway, and can be stale/misleading.)

**Environment variables** (set in `docker-compose.yml`'s `environment:` for
`payment-api` and `worker`; both already consumed by existing
`application.yml` files with no code changes required):
| Env var | Consumed as | Compose value | Applies to |
|---|---|---|---|
| `SPRING_DATASOURCE_URL` | `spring.datasource.url` (overrides the hardcoded local value via Spring env precedence) | `jdbc:postgresql://postgres:5432/payments` | payment-api, worker |
| `DB_USERNAME` | `${DB_USERNAME:${user.name}}` placeholder | `payguard` | payment-api, worker |
| `DB_PASSWORD` | `${DB_PASSWORD:}` placeholder | `payguard` | payment-api, worker |
| `REDIS_HOST` | `${REDIS_HOST:localhost}` placeholder | `redis` | payment-api, worker |
| `REDIS_PORT` | `${REDIS_PORT:6379}` placeholder | `6379` | payment-api, worker |

**Postgres container environment** (official `postgres:16-alpine` image
variables): `POSTGRES_DB=payments`, `POSTGRES_USER=payguard`,
`POSTGRES_PASSWORD=payguard`. These must match the `DB_USERNAME`/
`DB_PASSWORD`/database-name-in-URL values above exactly.

**Healthchecks**:
| Service | Command | interval | timeout | retries | start_period |
|---|---|---|---|---|---|
| `postgres` | `pg_isready -U payguard -d payments` | 5s | 5s | 5 | — |
| `redis` | `redis-cli ping` | 5s | 3s | 5 | — |
| `payment-api` | `wget --no-verbose --tries=1 --spider http://localhost:8080/ \|\| exit 1` | 5s | 3s | 10 | 15s |

**`depends_on` graph**:
- `payment-api` → `postgres` (`condition: service_healthy`), `redis`
  (`condition: service_healthy`)
- `worker` → `payment-api` (`condition: service_healthy`), `redis`
  (`condition: service_healthy`)

**Compose service names / hostnames**: `payment-api`, `worker`, `postgres`,
`redis` — these are both the Compose service keys and the DNS names other
containers use to reach them.

**Port mappings** (`docker-compose.yml`):
| Service | Mapping | Required for inter-container traffic? |
|---|---|---|
| `payment-api` | `8080:8080` | No (only needed for host access, e.g. curl/browser from the Mac) — but keep it, it's how you'll actually use the system |
| `postgres` | `5432:5432` | No — optional, for host-side `psql` debugging |
| `redis` | `6379:6379` | No — optional, for host-side `redis-cli` debugging |
| `worker` | none | N/A — no listener at all |

**Named volume**: `pg-data`, mounted at `/var/lib/postgresql/data` in the
`postgres` service. Declared under the top-level `volumes:` key.

**Networking**: default Compose-managed bridge network (no explicit
`networks:` block); all four services join it automatically.

## Open Questions

- [ ] Should a `make` target (e.g. `make compose-up`) wrap `docker compose up
      --build` for consistency with the existing `make start`/`make verify`
      direct-JVM flow? Not in `PHASE_3_TASKS.md`'s checklist, so left out of
      this design — routing to the user/orchestrator to decide whether it's
      in scope for this phase or a nice-to-have for later.
- [ ] Pin Postgres/Redis images to exact patch versions (e.g.
      `postgres:16.4-alpine`) instead of floating majors (`postgres:16-alpine`)?
      Floating majors auto-pick up patch/security fixes but make builds
      non-reproducible over time; exact pins are reproducible but need manual
      bumping. Left as a floating major tag in the Decision above since this
      is a learning project without a release-reproducibility requirement,
      but flagging in case the user wants the stricter habit now rather than
      later.

## Out of Scope

- `mock-downstream` and `chaos-injector` Dockerfiles — neither service exists
  in code yet (per `PROJECT_PLAN.md`'s roadmap, they arrive in a later phase).
- Kubernetes manifests, Helm charts (Phase 5).
- CI-driven image builds, tagging, and registry publishing (Phase 4).
- OpenTelemetry instrumentation, Prometheus/Grafana sidecars (Phase 7).
- Terraform-managed local infra (Phase 8).
- Adding `spring-boot-starter-actuator` for a "real" `/actuator/health`
  endpoint — the existing static test-portal page at `/` is sufficient as a
  healthcheck target for now; revisit if a later phase needs richer health
  signals (e.g. DB/Redis sub-component health) that a plain `wget` against
  `/` can't distinguish.
- Secrets management for `DB_PASSWORD` (hardcoded `payguard`/`payguard` in
  `docker-compose.yml`) — acceptable for a local-only learning stack; a real
  secrets mechanism (Docker secrets, k8s Secrets, Vault) is a later-phase
  concern once this leaves a single laptop.
- Automatic container restart policies — deliberately left at Compose's
  default (`no`); see Decision.
- Multi-arch image builds (`linux/amd64` + `linux/arm64`) — not needed since
  this runs only on the owner's own machine, not distributed to others.
