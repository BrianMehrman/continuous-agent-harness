# Project handoff

Checked 2026-10-04 against `origin/main` at `010ee2c` (merged PR #8). Verify GitHub and the checkout again before acting; this note is a handoff, not a live status feed.

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

## Next: Task 5

Implement the one-call scripted and local Ollama model adapters, durable conversation blobs, and frozen local profile revisions in [Task 5 of the implementation plan](plans/2026-09-20-local-task-tracker-implementation.md#task-5-implement-one-call-local-model-adapters-and-frozen-profiles). Keep model calls explicit: return native tool requests to the harness without executing tools or making automatic follow-up model calls. Test request/response mapping with local HTTP fixtures. Probe installed model tags, full digests, local residency, and a read-only tool round trip; reject cloud-backed profiles and tag drift. Require an explicitly selected `HARNESS_OLLAMA_MODEL`; do not auto-pull a model. Scripted tests precede live inference. Task 10 owns the actual local completion evidence.

Before starting Task 5, update local `main` from `origin/main`, confirm they match, then create the feature branch. Follow [AGENTS.md](../AGENTS.md) for Git, security, testing, PR, and resource cleanup rules.
