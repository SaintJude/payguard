# Phase 3 Notes — Containerize

## What was built

- `services/payment-api/Dockerfile` and `services/worker/Dockerfile` — both
  multi-stage (`build` on `maven:3.9-eclipse-temurin-21`, `runtime` on
  `eclipse-temurin:21-jre-alpine`), non-root `payguard` user, wildcard jar
  copy renamed to `app.jar`. `payment-api` exposes `8080`; `worker` exposes
  nothing (no HTTP surface).
- Matching `.dockerignore` for each service (`target/`, `.git/`, logs, etc.)
  so build context upload doesn't ship stale local build output.
- Root `docker-compose.yml` wiring `payment-api`, `worker`, `postgres`, and
  `redis` together — env-var-based config (no new Spring profile), a named
  volume (`pg-data`) for Postgres persistence, and healthcheck-gated startup
  ordering so `worker` can never start before `payment-api`'s Flyway
  migration has created the `payments` table.
- Full design rationale lives in `docs/architecture/PHASE_3_DESIGN.md`.

## Surprises / gotchas

- **Docker wasn't installed on the machine at all** — not "Docker Desktop
  stopped," genuinely absent (`command not found: docker`). Docker Desktop
  needs GUI first-run interaction (license acceptance, privileged helper
  install) that isn't automatable from a terminal-only session, so we used
  **colima** instead — a CLI-only Linux VM (via `lima`) running a real Docker
  daemon, plus the `docker` and `docker-compose` Homebrew formulae for the
  client and `docker compose` plugin. `colima start` handles the whole VM
  lifecycle; no GUI, no password prompts. Worth knowing for anyone reproducing
  this on a fresh Mac: `brew install colima docker docker-compose`, add
  `cliPluginsExtraDirs` pointing at `/opt/homebrew/lib/docker/cli-plugins` to
  `~/.docker/config.json` (otherwise `docker compose` isn't found even though
  `docker-compose` the standalone binary is), then `colima start`.
- **Port collisions with Phase 1's own artifacts.** By the time this phase
  ran, Homebrew's `postgresql@16`/`redis` services and a Phase 1 direct-JVM
  `payment-api`/`worker` pair (from `make start`) were all still bound to
  5432/6379/8080 — the exact ports Compose wanted to publish. The design doc
  had already flagged this exact scenario as a known caveat under the
  port-mapping note, so we checked `lsof`/`brew services list` and ran `make
  stop` + `brew services stop postgresql@16 redis` *before* the first
  `docker compose up`, avoiding the port-bind failure entirely rather than
  debugging it after the fact. Both are just stopped, not uninstalled —
  `brew services start postgresql@16 redis` brings Phase 1's direct-JVM mode
  back at any time.
- **The two custom subagents (`architect`, `implementer`) aren't directly
  spawnable** through this session's agent-dispatch tool — only a fixed set
  of built-in agent types is exposed, not whatever's defined under
  `.claude/agents/`. Worked around it by dispatching general-purpose agents
  with the target subagent's full persona/rules/output-contract pasted
  verbatim into the prompt. Behaviorally equivalent, but worth knowing this
  repo's `.claude/agents/*.md` files aren't automatically wired into every
  tool surface that can run subagents.
- No Dockerfile-level surprises — the design doc's illustrative snippet was
  precise enough that both Dockerfiles built and ran correctly on the first
  try, with no Open Questions needed from either implementer.

## What I'd do differently

Check for and stop conflicting local services (Homebrew, `make start` PIDs)
*before* generating `docker-compose.yml`'s port mappings, rather than
discovering the conflict at `docker compose up` time — the design doc already
had the knowledge to predict this, it just wasn't acted on until the port
bind failed.
