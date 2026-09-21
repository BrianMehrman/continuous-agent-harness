# Delivery roadmap

Status: Revised for Java/Spring Boot and first-version recovery; supporting design proposed

Date: 2026-09-20

This is a high-level roadmap. The first executable-task candidate is the [local coding implementation plan](2026-09-20-local-task-tracker-implementation.md), based on the [task-tracker benchmark](../specs/local-task-tracker-benchmark.md). The [durable foundation](../specs/spring-boot-durable-foundation.md) governs execution semantics.

| Phase | Deliverable | Exit gate |
|---|---|---|
| 1 — Durable observable loop | Local/remote profiles, bounded execution, verified artifact, durable history, console, telemetry, crash recovery, deployment continuation | All phase-one criteria including P1-16 through P1-19, actual local/remote evidence, real-service recovery/deployment tests |
| 2 — Interactive continuity | Messages, questions, safe pause/resume, explicit configuration revisions | Durable command redelivery and safe-boundary application verified |
| 3 — Coded graphs and workflows | Versioned definitions, branches, bounded cycles, joins, execution graph | Failure/cancellation/recovery semantics verified |
| 4 — Fleets and experiments | Delegation, isolated workers, mixed backends, evaluator comparisons | Comparable outcomes without duplicate task ownership |

## First implementation plan

The task-tracker CLI and local Ollama are accepted. Review the supporting Spring AI/Temporal design in ADR 0007 and the linked file-level plan before coding. The plan starts with dependency compatibility and real-service setup, then proves workspace/runner/evaluator behavior, durable orchestration, and actual local completion. Select the installed model through a capability probe before live acceptance.

## Phase-one slices

1. Deliver the local coding API/CLI slice: isolated persistent source, controlled build/test runner, independent evaluator, scripted and Ollama model adapters, durable commands, recovery/deployment checks, and real local benchmark evidence.
2. Add the browser console with reconnect, cancellation, artifact inspection and linked retry under a separate plan.
3. Add an authenticated remote provider and compare the same coding task against the local baseline.
4. Validate the complete phase-wide operator experience and local/remote evidence before claiming all original P1 criteria complete.

Instrumentation begins in slice one. Each slice is delivered through a pull request. The first slice proves recovery before expanding UI or agent patterns. Recurring execution and checkpoint forks require separate specifications.

## Delivery discipline

Implementation plans map reviewed specifications to exact files, interfaces, and test steps. Sprint records track progress. Never integrate directly into the default branch. Name database migrations for their schema purpose and verify filenames/history entries before committing.
