# Project handoff

Checked 2026-10-04 against `origin/main` at `e3446d6` (merged PR #11). Verify GitHub and the checkout again before acting; this note is a handoff, not a live status feed.

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

The credential hardening follow-up was merged in [PR #5](https://github.com/BrianMehrman/continuous-agent-harness/pull/5). Task 4's full build passed 92 Java tests on 2026-10-03; see [development evidence](development.md). This does not establish a live Ollama benchmark pass.

## In review: Task 6

Task 6 persists accepted start/cancel commands, stable run identity and frozen inputs, leased outbox delivery, and semantic event projections in [PR #12](https://github.com/BrianMehrman/continuous-agent-harness/pull/12). It uses a fake workflow gateway in integration tests; the Temporal gateway and coding workflow belong to Task 7. See [Task 6 of the implementation plan](plans/2026-09-20-local-task-tracker-implementation.md#task-6-deliver-accepted-commands-without-duplicate-runs).

The unit-test CI workflow was merged in [PR #11](https://github.com/BrianMehrman/continuous-agent-harness/pull/11) with `ubuntu-24.04` pinned. Task 5's opt-in live probe used the installed `qwen3.8:latest` tag and verified a read-only tool round trip and one adapter call; no model was pulled or full benchmark run claimed. The coding benchmark remains Task 10 work. See [development evidence](development.md#explicit-local-model-boundary-task-5). Confirm Task 6's PR actually merged before marking it complete or starting Task 7. Follow [AGENTS.md](../AGENTS.md) for Git, security, testing, PR, and resource cleanup rules.
