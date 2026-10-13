# Project handoff

Checked 2026-10-12 on `feat/work-queue-loop-console` while preparing its PR (commit `64a0439` plus documentation). Verify GitHub and the checkout again before acting; this note is a handoff, not a live status feed.

**Read first:** [Task 9 review and test-scope reset](reviews/2026-10-12-task9-review-and-test-scope.md) and the [test-cleanup plan](superpowers/plans/2026-10-12-main-feature-test-cleanup.md). After Task 9 merged, the owner directed removal of extra recovery, race, and deployment tests so the build checks the main features with less delay.

## Current milestone

The durable Spring Boot harness now has an in-progress browser console branch for free-text repository tasks. It shows active work, FIFO queue, history, recorded loop stages, checks, and a review diff. The original task-tracker benchmark remains a separate acceptance target; repository work is reviewable rather than independently benchmarked.

## Completed on main

| Task | Merged PR | Result |
|---|---|---|
| 1 | [#4](https://github.com/BrianMehrman/continuous-agent-harness/pull/4) | Java/Spring Boot durable foundation, Gradle, PostgreSQL, Temporal |
| 2 | [#6](https://github.com/BrianMehrman/continuous-agent-harness/pull/6) | Immutable workspace snapshots and controlled edits |
| 3 | [#7](https://github.com/BrianMehrman/continuous-agent-harness/pull/7) | Isolated build/test runner and recovery |
| 4 | [#8](https://github.com/BrianMehrman/continuous-agent-harness/pull/8) | Independent task-tracker evaluator and durable verdicts |
| 5 | [#10](https://github.com/BrianMehrman/continuous-agent-harness/pull/10) | One-call scripted and local Ollama adapters, conversation blobs, and frozen profiles |
| 6 | [#12](https://github.com/BrianMehrman/continuous-agent-harness/pull/12) | Durable run commands, leased delivery, frozen inputs, and semantic projections |
| 7 | [#13](https://github.com/BrianMehrman/continuous-agent-harness/pull/13) | Temporal coding workflow, six-tool router, durable model attempts, admission, and scripted smoke test |
| 8 | [#15](https://github.com/BrianMehrman/continuous-agent-harness/pull/15) | Loopback operator API and Python CLI for run lifecycle and artifacts |
| 9 | [#16](https://github.com/BrianMehrman/continuous-agent-harness/pull/16) | Pinned worker deployment continuity, process recovery checks, attempt telemetry, and operator procedures |

The credential hardening follow-up was merged in [PR #5](https://github.com/BrianMehrman/continuous-agent-harness/pull/5). Task 4's full build passed 92 Java tests on 2026-10-03; see [development evidence](development.md). This does not establish a live Ollama benchmark pass.

## Current: browser work console branch

The owner prioritized the usable UI over the originally numbered live task-tracker benchmark. This branch adds frozen repository intake, isolated edits, one admitted task at a time, a trusted offline Docker check image, host-generated review diffs, semantic events, and a React page packaged in the Boot JAR. The live local trial on 2026-10-12 used `qwen3.5:9b` and reached `READY_FOR_REVIEW` for run `run-0f1b5bd9e54335f64a49c0915990a302cd6d251aa0eca45bc85a69b53518fc07`; its host-owned `BUILD` receipt was `SUCCEEDED`, exit 0. A second delivered queued run was cancelled with `APPLIED` before admission. The generated diff changed only `README.md`; the live checkout was unchanged. A later headless-Chromium journey against commit `64a0439` verified a model, submitted from the keyboard, queued and cancelled a second task, reloaded mid-run without duplicate events, and downloaded a `README.md`-only diff with a `SUCCEEDED` build receipt. `RepositoryTaskIT` now drives a scripted failing build through the real workflow and Docker runner to `READY_FOR_REVIEW` with the failure visible. See [console evidence](evidence/repository-work-console.md).

The console has no task-tracker benchmark UI. It only shows a benchmark run that holds the shared slot, linked to `/api/runs/{id}`; benchmark start, progress, and evaluator verdicts remain CLI/API-only, as planned for Task 10.

The normal test suite should stay focused on main behavior. The user explicitly asked to avoid new recovery, race, or deployment suites because those slowed work substantially. The original Task 10 live task-tracker benchmark has still not passed and must not be inferred from this repository task.

## Original Task 10: live local benchmark

PR #16 merged on 2026-10-12. Before the merge, `python3 scripts/recovery_test.py --all` (7m 13s) and `python3 scripts/deployment_test.py --forward-and-rollback` (43s) passed against commit `bef2658` and Temporal 1.32. The Java, test, script, build, and config trees were unchanged by the rebase to `d837b70`; `./gradlew test bootJar --offline` and focused API/coding smoke integration tests passed on `d837b70`. See [Task 9 evidence](evidence/task9-recovery.md) for the exact checks and limits. A cancellation update can still block the single dispatcher while its pinned worker is absent; cross-run START liveness remains unproved. The disconnected-exporter test does not prove an outage during a real model call.

No real Ollama model has completed the task-tracker benchmark. The owner merged the test cleanup after Task 9: retain checks for safe edits, isolated build/test, the evaluator, model adapter, bounded coding loop, and API/CLI; avoid extra recovery, race, and deployment suites. [PR #19](https://github.com/BrianMehrman/continuous-agent-harness/pull/19) carried that cleanup; the prior Task 9 evidence remains historical. After the console PR merges, select an installed local model, run the read-only tool probe, and complete one live task-tracker run with a frozen profile, trusted evaluator verdict, and recorded artifacts. Diagnose a failed run from its evidence; do not count scripted or repository-task completion as benchmark acceptance. Then complete the remaining [Task 10 plan](plans/2026-09-20-local-task-tracker-implementation.md#task-10-run-the-real-local-benchmark-and-package-completion-evidence).

The unit-test CI workflow was merged in [PR #11](https://github.com/BrianMehrman/continuous-agent-harness/pull/11) with `ubuntu-24.04` pinned. Task 5's opt-in live probe used the installed `qwen3.8:latest` tag and verified a read-only tool round trip and one adapter call; no model was pulled or full benchmark run claimed. The coding benchmark remains Task 10 work. See [development evidence](development.md#explicit-local-model-boundary-task-5). Follow [AGENTS.md](../AGENTS.md) for Git, security, testing, PR, and resource cleanup rules.
