# Project handoff

Checked 2026-10-11 against `origin/main` at `d5d77cb` (merged PR #15). Verify GitHub and the checkout again before acting; this note is a handoff, not a live status feed.

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

The credential hardening follow-up was merged in [PR #5](https://github.com/BrianMehrman/continuous-agent-harness/pull/5). Task 4's full build passed 92 Java tests on 2026-10-03; see [development evidence](development.md). This does not establish a live Ollama benchmark pass.

## Next: Task 9

Prove process-level recovery, deadline enforcement, Temporal persistence, and pinned worker deployment continuity. See [Task 9 of the implementation plan](plans/2026-09-20-local-task-tracker-implementation.md#task-9-prove-crashes-deadlines-and-deployment-continuity). Current branch work adds pinned worker configuration, process restart and forward/rollback checks, and [initial operations procedures](operations.md). A coding-run recovery integration test now kills the worker during a model call, restarts the same build, and verifies receipt reconciliation, the terminal projection, and the unchanged snapshot. Recovery and deployment tests pass against Temporal 1.32. Temporal downtime/deadline tests, telemetry/exporter behavior, API/log/history secret scans, and remaining operator helpers are not yet covered. No live Ollama benchmark pass has been established.

The unit-test CI workflow was merged in [PR #11](https://github.com/BrianMehrman/continuous-agent-harness/pull/11) with `ubuntu-24.04` pinned. Task 5's opt-in live probe used the installed `qwen3.8:latest` tag and verified a read-only tool round trip and one adapter call; no model was pulled or full benchmark run claimed. The coding benchmark remains Task 10 work. See [development evidence](development.md#explicit-local-model-boundary-task-5). Follow [AGENTS.md](../AGENTS.md) for Git, security, testing, PR, and resource cleanup rules.
