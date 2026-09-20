# Sprint 01 — Observable local and remote execution

Status: Proposed; not started

Phase: 1

Spec: [Observable loop](../specs/phase-1-observable-loop.md)

## Objective

Demonstrate a bounded task on Java/Spring Boot against local and remote model profiles, with a verified artifact, inspectable history, and automatic continuation after crashes and compatible deployments. The [durable foundation design](../specs/spring-boot-durable-foundation.md) extends this scope.

## Prerequisites

Review the revised durable foundation and proposed ADR 0007, confirm supporting libraries and the database, and select the local and remote acceptance profiles. Java/Spring Boot is accepted. Turn this scope into a file-level implementation plan before coding. This record makes no calendar or velocity commitment.

## Work packages

| Work | Acceptance coverage | State |
|---|---|---|
| Fixture, deterministic verifier, scripted model, application/store foundation | P1-04, P1-10 | Not started |
| Bounded execution and immutable run configuration | P1-03, P1-05 | Not started |
| Model profiles and adapter contract coverage | P1-01, P1-02, P1-09, P1-12 | Not started |
| Durable events, crash recovery, deployment continuation, idempotent command delivery, uncertain effects, service restart | P1-06, P1-11, P1-14, P1-16, P1-17, P1-18, P1-19 | Not started |
| Console, cancellation, reconnect, linked retry | P1-07, P1-15 | Not started |
| Telemetry, failure scenarios, live acceptance, operating guide | P1-08, P1-13; end-to-end P1-01 | Not started |

## Completion evidence

- Automated acceptance matrix with meaningful behavior tests.
- Local and remote run IDs, immutable profile references, verifier outcomes, latency, and usage availability.
- Cancellation, worker/service crash recovery, forward deployment, rollback, command-delivery recovery, and uncertain-completion demonstrations.
- Correlated trace/log evidence and useful metrics, including exporter failure.
- Reproducible setup and operating instructions.
- Reviewed pull requests with no unresolved acceptance gaps represented as completed work.

Pause/resume, live messages, graph scheduling, and fleets are outside this sprint. If this scope exceeds one delivery interval, split it at the roadmap's vertical slices while retaining the phase-one exit gate.
