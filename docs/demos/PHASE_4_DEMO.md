# Phase 4 Demo — CI Pipeline

## What's new this phase

Every pull request and every push to `main` now automatically runs a
GitHub Actions pipeline: style/lint check, unit tests for both services, and
a Docker build for both services (proving the Phase 3 Dockerfiles still
build) — all on GitHub's infrastructure, not your laptop. A green badge on
the README reflects the health of `main` at a glance.

## See it yourself

1. Open the **Actions** tab on the repo:
   https://github.com/SaintJude/payguard/actions — you'll see past runs,
   including this phase's own PR, with four checks each:
   `test (payment-api)`, `test (worker)`, `docker-build (payment-api)`,
   `docker-build (worker)`.

2. Look at the badge at the top of `README.md` (also visible on the repo's
   GitHub page) — green means the last run against `main` passed.

3. To see it catch a real problem: open any PR that changes Java source, and
   watch the **Checks** tab on the PR. If a test fails or a file isn't
   formatted per `google-java-format`, the relevant check goes red and
   `docker-build` for that service won't even run (it's gated on `test`
   passing) — you'll see it reported as **skipped**, not just slow.

4. To fix a formatting failure locally before pushing:
   ```
   JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -f services/payment-api/pom.xml spotless:apply
   JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -f services/worker/pom.xml spotless:apply
   ```

## Stop

Nothing to stop — this runs on GitHub's servers, not locally.
