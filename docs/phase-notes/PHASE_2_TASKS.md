# Phase 2 Tasks — Git Discipline

Goal: turn the informal git habits from Phase 1 (conventional commit
messages, careful staging) into explicit, enforced practice — trunk-based
branching, PRs even solo, and branch protection.

## Setup
- [ ] Create a GitHub remote (`payguard`, private) and push `main`
- [ ] Add `.github/pull_request_template.md` (summary, test plan, phase reference)
- [ ] Enable branch protection on `main`: require a PR before merging (no
      direct pushes, even solo); require status checks once CI exists (Phase 4)
- [ ] Review `.gitignore` for completeness now that both services exist

## Workflow
- [ ] Adopt a branch naming convention off `main` (e.g. `phase-2/git-discipline`, `fix/...`)
- [ ] Document conventional commit format as a required convention (not just
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
