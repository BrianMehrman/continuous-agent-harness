# Sprint 01 — Durable local coding benchmark

Status: Proposed; not started

Phase: 1

Spec: [Local task-tracker benchmark](../specs/local-task-tracker-benchmark.md)

Plan: [Local coding implementation](../plans/2026-09-20-local-task-tracker-implementation.md)

The older phase-wide acceptance matrix remains a roadmap; remote-provider parity and browser-console coverage are subsequent milestones.

## Objective

Have a local Ollama model implement a Java task-tracker CLI, independently verify its code, and demonstrate same-run continuation after crashes and compatible deployments. Operate the first slice through an API/CLI with inspectable history, immutable source snapshots, and controlled container execution.

## Prerequisites

Review the supporting Temporal/Spring AI design and the file-level plan. The CLI target, Java/Spring Boot backend, first-version recovery, and Ollama-first integration are accepted. Choose/probe the installed model before live acceptance. No calendar or velocity commitment is made.

## Work packages

| Work | Plan tasks | State |
|---|---|---|
| Verified runtime and durable services | 1 | Not started |
| Immutable source snapshots and scoped edits | 2 | Not started |
| Isolated runner and independent evaluator | 3–4 | Not started |
| Local model, durable command delivery and coding loop | 5–7 | Not started |
| Operator API/CLI | 8 | Not started |
| Crashes, deployments, cancellation, telemetry and secret checks | 9 | Not started |
| Actual local coding runs and evidence | 10 | Not started |

## Completion evidence

All C1–C12 benchmark criteria must have evidence. Record the installed local model digest, task/evaluator version and seed, run ID, source/JAR digests, verifier outcomes, timing, usage availability, and recovery/deployment results. Mock-only success does not complete this milestone. A benchmark failure remains a failure; do not repair the candidate by hand or weaken verification.

Remote-provider parity, the React console, pause/resume, live messages, graph scheduling and fleets are outside this slice. Preserve those roadmap commitments without reporting them complete.
