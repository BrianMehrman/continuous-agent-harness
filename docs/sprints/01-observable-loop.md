# Sprint 01 — Durable local coding benchmark

Status: Closed 2026-10-17. Tasks 1–9 and the browser console merged (PRs #4–#20). The owner's 2026-10-17 [roadmap revision](../plans/roadmap.md) moved the Task 10 live benchmark pass to milestone M7. Single-model prompt iteration ended after five failed live runs ([evidence](../evidence/local-task-tracker.md)).

Phase: 1

Spec: [Local task-tracker benchmark](../specs/local-task-tracker-benchmark.md)

Plan: [Local coding implementation](../plans/2026-09-20-local-task-tracker-implementation.md)

The older phase-wide acceptance matrix remains a roadmap; remote-provider parity and browser-console coverage are subsequent milestones.

## Objective

Have a local Ollama model implement a Java task-tracker CLI, independently verify its code, and demonstrate same-run continuation after crashes and compatible deployments. Operate the first slice through an API/CLI with inspectable history, immutable source snapshots, and controlled container execution.

## Prerequisites

Review the supporting Temporal/Spring AI design and the file-level plan. The CLI target, Java/Spring Boot backend, first-version recovery, and Ollama-first integration are accepted. Choose/probe the installed model before live acceptance. No calendar or velocity commitment is made.

## Work packages

| Work | Plan tasks |
|---|---|
| Verified runtime and durable services | 1 |
| Immutable source snapshots and scoped edits | 2 |
| Isolated runner and independent evaluator | 3–4 |
| Local model, durable command delivery and coding loop | 5–7 |
| Operator API/CLI | 8 |
| Crashes, deployments, cancellation, telemetry and secret checks | 9 |
| Actual local coding runs and evidence | 10 |

## Completion evidence

All C1–C12 benchmark criteria must have evidence. Record the installed local model digest, task/evaluator version and seed, run ID, source/JAR digests, verifier outcomes, timing, usage availability, and recovery/deployment results. Mock-only success does not complete this milestone. A benchmark failure remains a failure; do not repair the candidate by hand or weaken verification.

Remote-provider parity, the React console, pause/resume, live messages, graph scheduling and fleets are outside this slice. Preserve those roadmap commitments without reporting them complete.
