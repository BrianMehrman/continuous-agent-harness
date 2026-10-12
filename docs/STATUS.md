# Project handoff

Checked 2026-10-12 against `origin/main` at `eff4e77` (merged PR #16). Verify GitHub and the checkout again before acting; this note is a handoff, not a live status feed.

**Read first:** [Task 9 review and test-scope reset](reviews/2026-10-12-task9-review-and-test-scope.md) (2026-10-12). The owner chose to finish Task 9, retain existing tests for now, add no unnecessary tests, and clean up the suite afterward.

## Current milestone

Build an API/CLI-operated Spring Boot harness that lets a local Ollama model implement the Java task-tracker CLI, independently evaluates its result, and resumes the same run after crashes and compatible deployments. The browser console and remote-provider comparison follow this milestone.

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

## Next: Task 10 live local benchmark

PR #16 merged on 2026-10-12. Before the merge, `python3 scripts/recovery_test.py --all` (7m 13s) and `python3 scripts/deployment_test.py --forward-and-rollback` (43s) passed against commit `bef2658` and Temporal 1.32. The Java, test, script, build, and config trees were unchanged by the rebase to `d837b70`; `./gradlew test bootJar --offline` and focused API/coding smoke integration tests passed on `d837b70`. See [Task 9 evidence](evidence/task9-recovery.md) for the exact checks and limits. A cancellation update can still block the single dispatcher while its pinned worker is absent; cross-run START liveness remains unproved. The disconnected-exporter test does not prove an outage during a real model call.

No real Ollama model has completed the task-tracker benchmark. The immediate priority is one live end-to-end run through the API/CLI with an installed local model, frozen profile, trusted evaluator verdict, and recorded artifacts. Select the installed model with the operator and run the read-only tool probe first. Diagnose any failed run from its evidence; do not count scripted completion or repair candidate source by hand. Then complete the remaining [Task 10 plan](plans/2026-09-20-local-task-tracker-implementation.md#task-10-run-the-real-local-benchmark-and-package-completion-evidence) and acceptance evidence. Keep the existing tests for now, add no unnecessary crash, race, recovery, or deployment tests, and clean up the suite after Task 9 as the owner requested. Revisit the dispatcher liveness gap and decide which live recovery/deployment scenarios are still needed after the baseline works.

The unit-test CI workflow was merged in [PR #11](https://github.com/BrianMehrman/continuous-agent-harness/pull/11) with `ubuntu-24.04` pinned. Task 5's opt-in live probe used the installed `qwen3.8:latest` tag and verified a read-only tool round trip and one adapter call; no model was pulled or full benchmark run claimed. The coding benchmark remains Task 10 work. See [development evidence](development.md#explicit-local-model-boundary-task-5). Follow [AGENTS.md](../AGENTS.md) for Git, security, testing, PR, and resource cleanup rules.
