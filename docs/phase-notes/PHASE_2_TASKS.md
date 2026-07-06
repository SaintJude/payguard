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
- [ ] Practice the full loop once: branch → commit → PR → merge via GitHub UI
      (pick squash vs. merge commit and document why)

## Manual verification
- [ ] Confirm a direct push to `main` is actually blocked
- [ ] Confirm the PR template appears automatically when opening a PR

## Wrap-up
- [ ] Write `docs/phase-notes/PHASE_2_NOTES.md`
- [ ] Write `docs/demos/PHASE_2_DEMO.md`
- [ ] Commit with message `docs: phase 2 — git discipline`
