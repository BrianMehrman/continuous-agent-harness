# Delivery roadmap

Status: Revised for Java/Spring Boot and first-version recovery; supporting design proposed

Date: 2026-09-20

This is a high-level roadmap, not an executable implementation plan. The [durable foundation](../specs/spring-boot-durable-foundation.md) governs execution semantics.

| Phase | Deliverable | Exit gate |
|---|---|---|
| 1 — Durable observable loop | Local/remote profiles, bounded execution, verified artifact, durable history, console, telemetry, crash recovery, deployment continuation | All phase-one criteria including P1-16 through P1-19, actual local/remote evidence, real-service recovery/deployment tests |
| 2 — Interactive continuity | Messages, questions, safe pause/resume, explicit configuration revisions | Durable command redelivery and safe-boundary application verified |
| 3 — Coded graphs and workflows | Versioned definitions, branches, bounded cycles, joins, execution graph | Failure/cancellation/recovery semantics verified |
| 4 — Fleets and experiments | Delegation, isolated workers, mixed backends, evaluator comparisons | Comparable outcomes without duplicate task ownership |

## First implementation plan

First review the supporting Spring AI/Temporal design in ADR 0007, confirm the incident fixture benchmark, and select live local/remote profiles. Verify and pin compatible dependency versions. Then write a file-level implementation plan before coding.

## Phase-one slices

1. Prove a scripted bounded task on Spring Boot with real persistence, deterministic verification, recoverable start delivery, worker crash continuation, and deployment compatibility.
2. Add local and authenticated remote model adapters with truthful usage, explicit retries, and secret-redaction contract tests.
3. Add operator read models and reconciliation, console, cancellation, reconnect, and linked retries.
4. Validate telemetry outages, service restart, uncertain Activity completion, actual local/remote benchmarks, and operating procedures.

Instrumentation begins in slice one. Each slice is delivered through a pull request. The first slice proves recovery before expanding UI or agent patterns. Recurring execution and checkpoint forks require separate specifications.

## Delivery discipline

Implementation plans map reviewed specifications to exact files, interfaces, and test steps. Sprint records track progress. Never integrate directly into the default branch. Name database migrations for their schema purpose and verify filenames/history entries before committing.
