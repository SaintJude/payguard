# Phase 4 Notes — CI

## What was built

- `.github/workflows/ci.yml`: triggers on PRs targeting `main` and pushes to
  `main`. Two jobs, both matrixed over `service: [payment-api, worker]` with
  `fail-fast: false`: `test` (Spotless lint check, then `mvn test`) and
  `docker-build` (`needs: test`, plain `docker build` per service, no
  registry push).
- Spotless (`spotless-maven-plugin` + `google-java-format`) added to both
  `pom.xml`s as the lint step. Ran `spotless:apply` once to reformat all
  existing source so the check starts green rather than immediately failing
  on pre-existing code.
- CI status badge on `README.md`.
- Full rationale in `docs/architecture/PHASE_4_DESIGN.md`, including two
  judgment calls confirmed with the user before implementation: GitHub
  Actions/GHCR don't count as "cloud provider dependencies" under
  `CLAUDE.md`'s rule, and registry push is deliberately deferred (nothing
  consumes a published image until Phase 5 at the earliest).

## Surprises / gotchas

- **Reformatting touched every existing source file.** `spotless:apply`
  rewrote all 23 `.java` files from 4-space indentation to
  `google-java-format`'s 2-space style plus import reordering — a big diff
  for what is, behaviorally, a no-op. Verified via `git diff` review and a
  full test-suite pass (21/21) that nothing beyond formatting changed. Worth
  knowing for future projects: adding a formatter to an established codebase
  always produces this one-time "reformat everything" commit — better to do
  it deliberately in its own commit (which this was) than have it show up
  mixed into an unrelated change later.
- **Homebrew's unversioned `openjdk` (now JDK 26) broke Spotless locally**,
  the same way it broke Lombok in Phase 1 — `google-java-format` uses javac
  internals that don't exist under JDK 26. Same fix as the Makefile already
  uses: pin `JAVA_HOME=/opt/homebrew/opt/openjdk@21` for local Maven
  invocations. Doesn't affect CI itself, since `actions/setup-java@v4`
  installs an exact, versioned JDK 21 on the runner regardless of what's on
  the host laptop — this is arguably a point in favor of CI existing at all:
  it can't be broken by a local environment drift the way `mvn` on a laptop
  can.
- **The `needs: test` gate proved itself for real, not just on paper.** The
  deliberate-breakage verification (see Manual Verification in
  `PHASE_4_TASKS.md`) showed `docker-build` reporting `skipping` rather than
  running once `test (worker)` failed — confirming the dependency actually
  short-circuits wasted runner time on code that doesn't pass its own tests,
  exactly as designed.
- **`fail-fast: false` also proved itself for real**: in that same
  deliberate-breakage run, `test (worker)` failed while `test (payment-api)`
  kept running and passed independently in the same workflow run, instead of
  being cancelled the moment its sibling failed.
- Minor: GitHub's check-runs API (`gh api repos/<owner>/<repo>/commits/<ref>/check-runs`)
  is a fast way to confirm exactly which check names exist and would be
  selectable in branch protection's required-checks dropdown, without
  needing to actually open the settings UI.

## What I'd do differently

Nothing major — this phase went close to plan. If repeating: run
`spotless:apply` as its own standalone commit *before* wiring up the CI
workflow that enforces it (rather than in the same commit), so a future
`git blame`/`git log` cleanly separates "we adopted a formatter" from "we
added CI" as two distinct, independently revertible decisions.
