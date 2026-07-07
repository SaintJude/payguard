# Phase 4 Tasks — CI

Goal: a GitHub Actions workflow that runs on every PR and every push to
`main` — lint, unit test, and build both Docker images (proving the
Dockerfiles from Phase 3 still build cleanly) — with a status badge on the
README. Per `PROJECT_PLAN.md`, publishing images to a registry (GHCR or a
local one) is optional for this phase.

## Setup
- [ ] Choose and wire up a lint/static-analysis step for both Java services
      (see design doc for the concrete choice and why)
- [ ] `.github/workflows/ci.yml` (or per-service workflows) running: lint →
      `mvn test` for both services → `docker build` for both Dockerfiles
- [ ] Confirm the workflow triggers on pull requests targeting `main` and on
      pushes to `main`
- [ ] Add a CI status badge to `README.md`

## Manual verification
- [ ] Open a throwaway PR (or push a small commit) and confirm the workflow
      actually runs in the Actions tab and all jobs pass
- [ ] Deliberately break something (e.g. a failing test or a lint violation)
      on a branch and confirm CI fails red, then revert
- [ ] Confirm required status checks can be enabled on the branch protection
      rule from Phase 2 (even if not turned on yet, confirm the check names
      are visible to pick from in GitHub's branch protection settings)

## Wrap-up
- [ ] Write `docs/phase-notes/PHASE_4_NOTES.md`
- [ ] Write `docs/demos/PHASE_4_DEMO.md`
- [ ] Update root `README.md` (badge) and `CLAUDE.md`'s Current Phase line
- [ ] Commit with message `feat: phase 4 — CI pipeline`
