# Main Feature Test Cleanup Implementation Plan

> **For agentic workers:** Implement this plan inline. Do not add crash, race, recovery, or deployment tests.

**Goal:** Make the default Gradle build exercise a small set of main-feature checks so feedback is fast enough to support the first live Ollama run.

**Architecture:** Keep the production harness and private evaluator fixtures. Remove redundant and process-recovery test classes from the default suite, retaining direct coverage of workspace safety, isolated build/test, evaluator verdict, model adapter, coding loop, and operator API/CLI. Remove test-only scripts that invoke deleted classes and update active instructions; preserve historical evidence as a record of what passed before cleanup.

**Tech Stack:** Java 21, JUnit 5, Spring Boot, Gradle wrapper, PostgreSQL, Temporal, Docker.

**Spec:** `docs/reviews/2026-10-12-task9-review-and-test-scope.md` and the owner's 2026-10-12 direction to remove extra tests after Task 9 merged. This plan moves cleanup ahead of the live run at the owner's latest request.

## Global Constraints

- Keep production behavior, migrations, Gradle dependency locks, benchmark starter, and private evaluator fixtures unchanged.
- Keep existing checks of the six main features named in `AGENTS.md`.
- Use the primary checkout and a dedicated branch from current `origin/main`; integrate through a PR only.
- Do not claim a scripted fixture proves live Ollama success.

## Review Focus

- A retained test must not depend on a deleted helper or script.
- `./gradlew test` must still cover the bounded coding loop and Ollama protocol.
- `./gradlew build` must still run main-feature integration checks, including the trusted evaluator and real Docker isolation.
- Active documentation must not tell an operator to invoke a deleted script or class.
- Historical Task 9 evidence must remain clearly historical after its reproduction helpers are removed.

## Task 1: Establish the baseline and dependency map

- [x] Record current Java test class/case count and `./gradlew test --offline` duration.
- [x] Map references to recovery helpers, scripts, and retained shared helpers (`UnversionedCodingWorkflow`, `CodingWorkflowSmokeIT.FixtureActivities`).
- [x] List each class to keep or remove against the main-feature table in the spec.

## Task 2: Trim unit tests

- [x] Remove the spec's redundant tests from `OllamaAdapterTest`, `ProfileProbeTest`, `ScriptedModelAdapterTest`, `CodingWorkflowTest`, `ToolRouterTest`, and `RoleConfigurationTest` while retaining their named main-feature cases.
- [x] Delete `ApiCommandPumpTest`, `ProfileControllerTest`, `RunTelemetryTest`, `InvocationHasherTest`, and `TemporalWorkflowGatewayTest` if no retained source references them.
- [x] Run `./gradlew test --offline` and resolve compile failures caused by unused imports or deleted helpers.

## Task 3: Trim integration tests and retain a Docker feature check

- [x] Keep `WorkspaceStoreIT`, `TaskTrackerEvaluatorIT`, `RunApiIT`, `ApiWorkflowIT`, `CodingWorkflowSmokeIT`, and opt-in `LocalOllamaProbeIT`; trim their cases without modifying private fixtures. The evaluator keeps one passing candidate and one forged-result rejection. Measurement showed five evaluator cases alone took 7m 18s of an 8m 8s build; the already retained `RunnerIT` checks host isolation. Historical Task 4/9 evidence records the removed variants.
- [x] Move the three main-feature Docker cases from `RunnerRecoveryIT` into `RunnerIT`, replacing process-crash setup with direct runner invocation and cleanup.
- [x] Remove the other recovery, deployment, durability, wiring, and telemetry integration classes and now-unused test-only process helpers after a reference scan. Keep `UnversionedCodingWorkflow` for retained workflow checks.
- [x] Compile test sources, then run the retained integration tests against the local dependencies.

## Task 4: Remove obsolete test entry points and refresh guidance

- [x] Delete `scripts/recovery_test.py`, `scripts/deployment_test.py`, `scripts/runner_fault_test.py`, and `config/recovery-test.yaml` once their target classes are gone.
- [x] Update `docs/development.md`, `docs/operations.md`, `AGENTS.md`, and other active instructions that invoke removed tests. Keep Task 9 evidence but label its commands historical and no longer runnable at HEAD.
- [x] Update `docs/STATUS.md` with the new test scope and the still-unproven live benchmark; avoid duplicating task status elsewhere.
- [x] Check document links and `git diff --check`.

## Task 5: Verify and deliver

- [ ] Run `./gradlew test --offline` and `./gradlew build --offline` with only the local services needed by retained integration tests; record command, date, commit, case count, and elapsed time. A final post-commit build is pending.
- [ ] Inspect the full diff and staged files for production changes, broken references, and sensitive material.
- [ ] Commit, push the branch, and open a PR. Leave merging to the owner.

## Execution evidence

- Baseline on 2026-10-12 before cleanup: `./gradlew test --offline --rerun-tasks` found 86 unit cases and took 18.7 seconds on the then-current main tree (`ce92372`).
- Reduced unit suite: 55 cases across 10 classes.
- Intermediate reduced working-tree build: `./gradlew build --offline` took 8m 8s; five evaluator cases consumed 7m 18s.
- Final reduced working-tree measurement on 2026-10-12: `./gradlew build --offline` with the pinned local `HARNESS_RUNNER_IMAGE` took 2m 39s: 55 unit cases and 13 integration cases (one opt-in live probe skipped). The evaluator's positive and negative cases consumed 118 seconds. Post-commit verification will establish the commit-specific pass claim.
- The main-feature scope retains safe editing, Docker isolation, trusted evaluator verdicts, model adapter, coding loop, and API/CLI checks. No production source, migration, dependency lock, benchmark starter, or private fixture changed.
