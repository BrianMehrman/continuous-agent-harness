# Project handoff

Checked 2026-10-04 against `origin/main` at `e4669f6` (merged PR #9). Verify GitHub and the checkout again before acting; this note is a handoff, not a live status feed.

## Current milestone

Build an API/CLI-operated Spring Boot harness that lets a local Ollama model implement the Java task-tracker CLI, independently evaluates its result, and resumes the same run after crashes and compatible deployments. The browser console and remote-provider comparison follow this milestone.

## Completed on main

| Task | Merged PR | Result |
|---|---|---|
| 1 | [#4](https://github.com/BrianMehrman/continuous-agent-harness/pull/4) | Java/Spring Boot durable foundation, Gradle, PostgreSQL, Temporal |
| 2 | [#6](https://github.com/BrianMehrman/continuous-agent-harness/pull/6) | Immutable workspace snapshots and controlled edits |
| 3 | [#7](https://github.com/BrianMehrman/continuous-agent-harness/pull/7) | Isolated build/test runner and recovery |
| 4 | [#8](https://github.com/BrianMehrman/continuous-agent-harness/pull/8) | Independent task-tracker evaluator and durable verdicts |

The credential hardening follow-up was merged in [PR #5](https://github.com/BrianMehrman/continuous-agent-harness/pull/5). Task 4's full build passed 92 Java tests on 2026-10-03; see [development evidence](development.md). This does not establish a live Ollama benchmark pass.

## In review: Task 5

The one-call scripted and local Ollama adapters, durable conversation blobs, and frozen local profile revisions are implemented on `feat/task5-local-model-adapters` for PR review. The operator selected the installed `qwen3.8:latest` tag through `HARNESS_OLLAMA_MODEL`; the opt-in live probe verified its local digest, read-only tool round trip, persisted revision, and one adapter call. No model was pulled. The coding benchmark remains Task 10 work. See [Task 5 of the implementation plan](plans/2026-09-20-local-task-tracker-implementation.md#task-5-implement-one-call-local-model-adapters-and-frozen-profiles) and [development evidence](development.md#explicit-local-model-boundary-task-5).

After Task 5 merges, Task 6 is next: deliver accepted commands without duplicate runs. Confirm the PR actually merged before updating this handoff as completed or starting Task 6. Follow [AGENTS.md](../AGENTS.md) for Git, security, testing, PR, and resource cleanup rules.
