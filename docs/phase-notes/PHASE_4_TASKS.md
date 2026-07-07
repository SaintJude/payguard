# Phase 4 Tasks — CI

Goal: a GitHub Actions workflow that runs on every PR and every push to
`main` — lint, unit test, and build both Docker images (proving the
Dockerfiles from Phase 3 still build cleanly) — with a status badge on the
README. Per `PROJECT_PLAN.md`, publishing images to a registry (GHCR or a
local one) is optional for this phase.

## Setup
- [x] Choose and wire up a lint/static-analysis step for both Java services
      (see design doc for the concrete choice and why) — Spotless +
      google-java-format
- [x] `.github/workflows/ci.yml` running: lint → `mvn test` for both
      services (matrixed) → `docker build` for both Dockerfiles (gated on
      `test` passing)
- [x] Confirm the workflow triggers on pull requests targeting `main` and on
      pushes to `main`
- [x] Add a CI status badge to `README.md`

## Manual verification
- [x] Open a throwaway PR (or push a small commit) and confirm the workflow
      actually runs in the Actions tab and all jobs pass — PR #7
      (`phase-4/ci`): all four checks (`test (payment-api)`, `test (worker)`,
      `docker-build (payment-api)`, `docker-build (worker)`) ran on real
      GitHub-hosted runners and passed
- [x] Deliberately break something (e.g. a failing test or a lint violation)
      on a branch and confirm CI fails red, then revert — PR #8 (throwaway,
      closed without merging): flipped an assertion in
      `DownstreamProcessorTest`, pushed, watched `test (worker)` fail red
      while `test (payment-api)` still ran and passed independently
      (`fail-fast: false` proven live), and `docker-build` reported
      `skipping` — confirming `needs: test` actually gates the build. Branch
      deleted after confirming; main was never touched by the breakage.
- [x] Confirm required status checks can be enabled on the branch protection
      rule from Phase 2 — queried the check-runs API on the PR #7 commit;
      all four names are now live and would be selectable in GitHub's branch
      protection settings. Not turned on this phase, per scope.

## Wrap-up
- [x] Write `docs/phase-notes/PHASE_4_NOTES.md`
- [x] Write `docs/demos/PHASE_4_DEMO.md`
- [x] Update root `README.md` (badge) and `CLAUDE.md`'s Current Phase line
- [x] Commit with message `feat: phase 4 — CI pipeline`
