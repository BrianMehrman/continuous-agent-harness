# Spring Boot and durable execution foundation

Status: Revised design for review; Java/Spring Boot and first-version recovery requirements accepted

Date: 2026-09-20

## Authority and scope

This design replaces the original phase-one executor and stack recommendations. [ADR 0006](../adr/0006-java-spring-boot-foundation.md) accepts Java/Spring Boot. [ADR 0007](../adr/0007-durable-execution-foundation.md) proposes Temporal and the supporting Spring AI integration. The older architecture remains useful for model/runtime separation, scoped configuration, tool restrictions, and diagnostic policy; conflicting executor ownership and restart behavior are superseded by this design.

The first release runs one bounded incident-report task with local and remote profiles, deterministic verification, an operator console, observability, and automatic continuation after a worker crash or compatible deployment. Interactive messages, manual pause/resume, arbitrary shell tools, graph scheduling, fleets, and multi-user hosting remain later work.

## Components and ownership

| Component | Responsibility |
|---|---|
| Spring Boot API | Validate commands, resolve immutable configuration, expose operator queries |
| Temporal Workflow | Own run lifecycle, loop decisions, limits, cancellation ordering, recovery |
| Model Activity | One model invocation through a Spring AI-backed adapter; return normalized content/tool requests and truthful usage |
| Tool Activity | One validated, scoped tool invocation with stable identity and explicit retry policy |
| Application PostgreSQL | Configuration, durable command delivery records, artifacts, rebuildable operator projections |
| React console | Start/list/detail, progress, results, cancel, linked retry |
| Diagnostic pipeline | Correlated traces, metrics, logs; never an execution authority |

The API and worker can begin in one codebase and deployment unit, with independently restartable worker instances for recovery tests. Temporal is a separate service with persistent storage. A worker restart must not erase service history. A service outage delays progress until service and persistence recover; recovery is not a promise of availability during an outage.

## Durable loop

1. Validate task input and actual adapter/model capabilities before accepting a run. Resolve and freeze configuration and definition identity without secret values.
2. Accept the start command durably. Assign a stable run/workflow identity and recoverably deliver the start request. Repeating the command returns the same run; reject conflicting reuse of its identity.
3. The Workflow checks remaining call limits, absolute execution deadline, and cancellation before scheduling each Activity.
4. A Model Activity returns content or tool requests. The Workflow validates requested operations and schedules each Tool Activity separately, then supplies recorded results to the next model call.
5. Verify the final structured artifact deterministically. Persist the artifact idempotently before publishing a successful result.
6. A new worker replays recorded workflow decisions and completed Activity results, then resumes outstanding work. An Activity whose completion was not recorded can execute again; recovery is not a Java stack snapshot.

All network and database operations stay outside Workflow code. Use workflow-safe clock/timer APIs. Configure bounded Activity timeouts and explicit retry policies, rather than inheriting provider SDK, Spring AI, and Temporal defaults that multiply attempts invisibly.

The demonstration retains the original logical limits: 12 model calls, 24 tool calls, 120 seconds per model attempt, 10 seconds per tool attempt, and a 10-minute execution deadline starting when execution begins. Queue time is separate. The absolute deadline continues during downtime; an expired run stops instead of receiving a new budget after restart. Infrastructure attempts must be bounded and accounted for separately from logical calls. Uncertain model completion is visible and may produce duplicate cost; never invent exact usage for a lost response.

## Commands and query consistency

Temporal is the single execution-state authority. The application read model can lag and must expose that condition. Projection writes are idempotent using stable run/event identity and ordered sequence; consumers replay or reconcile missing updates after failures. Do not claim atomicity between an application database transaction and a Temporal operation.

The implementation plan must define a recoverable delivery protocol, such as transactional command outbox plus idempotent dispatch. Test crashes before and after each delivery acknowledgment. Command acceptance and workflow application are separate states. Start deduplication must remain valid after workflow completion and across the configured retention window.

Cancellation becomes effective when the Workflow applies it. If cancellation is applied before success, it prevents success and future calls; an already-completed run rejects it. The console distinguishes a queued cancellation request from an applied one. In-flight provider work may continue despite local cancellation.

## Recovery and deployment

Use a stable definition version and immutable profile revision for every run. Application restarts do not mark active runs terminally interrupted. The operator can show worker unavailability or recovery pending while preserving lifecycle state.

Before the first release, choose and test the supported Temporal worker deployment/versioning mechanism against pinned server and SDK versions. Existing runs either remain assigned to compatible workers until completion or move only through a replay-compatible upgrade. Do not terminate required old workers before their runs have a supported continuation path. Test rollback as well as forward deployment.

Persist only required model context and results in durable payloads. Keep credentials out of Workflow inputs, results, history, logs, and projections; resolve references inside Activities. Bound payload sizes and use durable artifact references for large content. A future long-running workload must also bound history growth; phase one remains a bounded loop.

## Acceptance additions

| ID | Required evidence |
|---|---|
| P1-11 | Kill the worker between completed steps; restart and finish the same run without reissuing recorded completed model/tool calls |
| P1-16 | Deploy a changed worker while a run is active; the same run finishes with compatible definition/configuration behavior; demonstrate rollback and incompatible-change rejection/routing |
| P1-17 | Crash an Activity after a simulated effect but before completion acknowledgment; retry/reconciliation prevents duplicate fixture test effects and records uncertain model usage |
| P1-18 | Crash around command dispatch and projection writes; accepted starts survive, duplicate starts do not create another run, operator history converges |
| P1-19 | Restart the Temporal service with persistent storage; history survives and execution resumes; execution deadline still applies |

Use real Temporal and PostgreSQL for process-crash/deployment acceptance. Workflow unit tests alone are insufficient. Retain the original profile, validation, verifier, secret-redaction, cancellation, observability-outage, and live local/remote acceptance criteria.

## Review and implementation sequence

1. Review this supporting design and ADR 0007; confirm the fixture benchmark and actual local/remote profiles.
2. Check Java, Spring Boot, Spring AI, Temporal SDK/server, and telemetry compatibility; pin supported versions in the implementation plan.
3. Write a file-level plan for a recovery vertical slice: scripted model, deterministic tool/verifier, real service persistence, durable start, crash continuation, and deployment compatibility.
4. Implement the approved slice, then add live adapters, operator views, and operational acceptance through scoped pull requests.

No implementation plan or executable application is claimed by this document. The first slice must prove recovery before UI breadth or additional agent patterns.
