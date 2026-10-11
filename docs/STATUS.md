# Project handoff

Checked 2026-10-11 against `origin/main` at `9c2090b` (merged PR #13). Verify GitHub and the checkout again before acting; this note is a handoff, not a live status feed.

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

The credential hardening follow-up was merged in [PR #5](https://github.com/BrianMehrman/continuous-agent-harness/pull/5). Task 4's full build passed 92 Java tests on 2026-10-03; see [development evidence](development.md). This does not establish a live Ollama benchmark pass.

## Next: Task 8

Expose the local operator API and command-line client for run submission, observation, cancellation, and artifact retrieval. See [Task 8 of the implementation plan](plans/2026-09-20-local-task-tracker-implementation.md#task-8-expose-the-local-operator-api-and-command-line-client). Task 7's scripted real-service smoke test and full build passed locally before merge; see [development evidence](development.md#durable-coding-workflow-task-7). There is no public coding-run submission surface or live Ollama benchmark pass yet.

The unit-test CI workflow was merged in [PR #11](https://github.com/BrianMehrman/continuous-agent-harness/pull/11) with `ubuntu-24.04` pinned. Task 5's opt-in live probe used the installed `qwen3.8:latest` tag and verified a read-only tool round trip and one adapter call; no model was pulled or full benchmark run claimed. The coding benchmark remains Task 10 work. See [development evidence](development.md#explicit-local-model-boundary-task-5). Follow [AGENTS.md](../AGENTS.md) for Git, security, testing, PR, and resource cleanup rules.
