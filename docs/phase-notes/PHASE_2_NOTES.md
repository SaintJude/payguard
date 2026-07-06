# Phase 2 Notes — Git Discipline

## What was built

A GitHub remote, branch protection on `main` (PR required, no direct pushes,
applies even to the repo owner), a PR template, an explicit conventional-commit
convention in `CLAUDE.md`, a cleaned-up `.gitignore`, and one full practice
rep of branch → commit → PR → squash-merge → branch deletion.

## Surprises / gotchas

- **Free-tier private repos get zero branch protection** — neither the
  classic API nor the newer rulesets API will enable it; both return
  "Upgrade to GitHub Pro or make this repository public." We switched the
  repo to public specifically to unlock real enforcement, trading privacy
  for the actual learning goal of this phase.
- **Squash-merged branches confuse git's local safety check**: `git branch
  -d` refused to delete the merged branch ("not fully merged") because
  squash-merging creates a brand-new commit that isn't a descendant of the
  original branch tip. `git branch -D` (force) is the normal, safe thing to
  reach for here — not a red flag.
- **PR template auto-fill is purely a browser convenience**, not a
  server-side GitHub feature. A PR created via the raw API with no body came
  back `"body": null` — so this specific behavior can only ever be eyeballed
  in an actual browser, never verified from a script or CI.
- Two actions got correctly blocked by Claude Code's auto-mode safety
  classifier this phase: a test push straight to `main`, and a branch
  deletion — both because a vague "go ahead" / "ok, all done" didn't
  specifically name that destructive action. Good friction, not a bug.

## What I'd do differently

Decide public-vs-private up front, before creating the repo — we created it
private, hit the Pro-only wall, then had to switch visibility mid-phase.
Knowing the free-tier constraint going in would have skipped a step.
