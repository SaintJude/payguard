# Phase 2 Tasks — Git Discipline

Goal: turn the informal git habits from Phase 1 (conventional commit
messages, careful staging) into explicit, enforced practice — trunk-based
branching, PRs even solo, and branch protection.

## Setup
- [x] Create a GitHub remote and push `main` — repo is `SaintJude/payguard`,
      **public** (not private as originally planned: GitHub only offers
      branch protection on free-tier private repos, so we switched to public
      to get real enforcement — see PHASE_2_NOTES.md)
- [x] Add `.github/pull_request_template.md` (summary, test plan, phase reference)
- [x] Enable branch protection on `main`: require a PR before merging (no
      direct pushes, even solo, `enforce_admins: true`); required status
      checks deferred until CI exists (Phase 4)
- [x] Review `.gitignore` for completeness now that both services exist

## Workflow
- [x] Adopt a branch naming convention off `main` (e.g. `phase-2/git-discipline`, `fix/...`)
- [x] Document conventional commit format as a required convention (not just
      observed practice) — which types are used (`feat`, `fix`, `docs`,
      `test`, `chore`) and when
- [x] Practice the full loop once: branch → commit → PR → merge via GitHub UI
      — `phase-2/git-discipline` → PR #1 → squash-merged as `911a67f`. Chose
      squash (and locked the repo to squash-only) so `main`'s history stays
      one-commit-per-PR regardless of how messy a branch's WIP commits get.

## Manual verification
- [x] Confirm a direct push to `main` is actually blocked — attempted, rejected
      with `GH006: Protected branch update failed for refs/heads/main`
- [x] Confirm the PR template appears when opening a PR — verified this is
      client-side-only browser behavior (a PR created via the raw API with no
      body came back `"body":null`, no server-side injection), so it can only
      be eyeballed by opening the "new pull request" page in an actual
      browser; not something worth automated enforcement

## Wrap-up
- [x] Write `docs/phase-notes/PHASE_2_NOTES.md`
- [x] Write `docs/demos/PHASE_2_DEMO.md`
- [x] Commit with message `docs: phase 2 — git discipline`
