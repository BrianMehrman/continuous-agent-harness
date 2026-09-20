# Sprint 01 — Observable local and remote execution

Status: Proposed; not started

Phase: 1

Spec: [Observable loop](../specs/phase-1-observable-loop.md)

## Objective

Demonstrate a bounded task running through the same harness against local and remote model profiles, with a verified artifact and inspectable execution history.

## Prerequisites

Review the architecture and phase-one acceptance criteria, choose the implementation stack and database, and select the local and remote acceptance profiles. Turn this scope into a file-level implementation plan before coding. This record makes no calendar or velocity commitment.

## Work packages

| Work | Acceptance coverage | State |
|---|---|---|
| Fixture, deterministic verifier, scripted model, application/store foundation | P1-04, P1-10 | Not started |
| Bounded execution and immutable run configuration | P1-03, P1-05 | Not started |
| Model profiles and adapter contract coverage | P1-01, P1-02, P1-09, P1-12 | Not started |
| Durable events, restart semantics, idempotent commands | P1-06, P1-11, P1-14 | Not started |
| Console, cancellation, reconnect, linked retry | P1-07, P1-15 | Not started |
| Telemetry, failure scenarios, live acceptance, operating guide | P1-08, P1-13; end-to-end P1-01 | Not started |

## Completion evidence

- Automated acceptance matrix with meaningful behavior tests.
- Local and remote run IDs, immutable profile references, verifier outcomes, latency, and usage availability.
- Cancellation and restart demonstrations.
- Correlated trace/log evidence and useful metrics, including exporter failure.
- Reproducible setup and operating instructions.
- Reviewed pull requests with no unresolved acceptance gaps represented as completed work.

Pause/resume, live messages, graph scheduling, and fleets are outside this sprint. If this scope exceeds one delivery interval, split it at the roadmap's vertical slices while retaining the phase-one exit gate.
