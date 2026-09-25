# ADR 0007: Use Temporal for durable execution from phase one

Status: Proposed

Date: 2026-09-20

Would replace: executor ownership and crash behavior in [ADR 0001](0001-modular-application.md) and [ADR 0003](0003-durable-run-state.md)

## Context

Automatic continuation after crashes and deployments is an accepted first-version requirement. Persisting a run record and marking it interrupted does not meet that requirement. The language decision is recorded in [ADR 0006](0006-java-spring-boot-foundation.md).

## Decision proposed

Use the Temporal Java SDK. Temporal owns execution history, scheduling, durable timers, recovery, and workflow state. Java Workflow code coordinates a bounded agent loop; separate Activities perform each model call and each tool execution. Keep workflow decisions deterministic. External I/O, secret resolution, model calls, and database writes happen in Activities or application services, never directly in replayed Workflow code.

Use Spring AI behind the model adapter, with framework-managed automatic tool execution disabled for that path. The Workflow schedules validated tools explicitly. Do not put an entire multi-turn agent loop inside one Activity.

PostgreSQL holds application configuration, command-delivery records, artifacts, and rebuildable operator projections. It does not independently schedule or advance workflow state. Temporal service persistence is operationally separate from application tables, even if both use PostgreSQL.

The [local coding implementation plan](../plans/2026-09-20-local-task-tracker-implementation.md) proposes the start/command outbox, projection reconciliation, immutable source/blob storage, bounded attempt rules, and pinned worker deployments. It also adds an independently supervised container runner and evaluator for the accepted task-tracker benchmark. This ADR remains proposed pending review of those supporting choices.

## Alternatives

- **Custom database-backed executor:** possible, but requires implementing recovery, durable waits, ownership, delivery, and deployment compatibility as core infrastructure.
- **AI-framework agent loop alone:** useful for model/tool abstractions, but not sufficient evidence of recovery and deployment compatibility.

## Consequences

Adds a Temporal service and deterministic workflow constraints to local operation. Persisted workflow history does not guarantee exactly-once external effects. Activities with uncertain completion require safe retries or reconciliation; model retries may incur another charge.

Deployment compatibility is mandatory. Retain compatible worker versions for existing runs or make replay-compatible changes with tested versioning. Persist definition/profile identity, and do not silently switch an existing run to new defaults. Long histories need a tested history-bounding strategy before long-lived workloads expand.

## Revisit when

An implementation spike fails the recovery/deployment acceptance tests or service operation proves unsuitable for the target environment.

## References

- [Temporal Java SDK](https://github.com/temporalio/sdk-java)
- [Java workflow versioning](https://docs.temporal.io/develop/java/versioning)
- [Spring AI tool calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
