# ADR 0005: Use TypeScript and PostgreSQL as the application foundation

Status: Superseded by [ADR 0006](0006-java-spring-boot-foundation.md)

Historical proposal retained for rationale; it is not the current stack baseline.

Date: 2026-09-20

## Context

The project combines a browser operator console, asynchronous agent execution, local and remote model adapters, and later worker coordination. The initial architecture should be approachable locally while avoiding a language boundary between the console and application contracts.

## Decision

Recommend TypeScript on Node.js for the application, worker, and code-defined workflows, with a React operator console. Use PostgreSQL as the authoritative transactional store from phase 1. Keep one repository and one deployable application initially. Run dependency services through Docker Compose; Kubernetes is not a prerequisite.

The supporting Fastify, Vite, Drizzle, test, and telemetry recommendations are documented in the [stack specification](../specs/implementation-stack.md). They do not each warrant an ADR.

## Alternatives

- **Rails and PostgreSQL:** strong application conventions and a relevant observability example, but less shared language across a React console and execution definitions.
- **Python and PostgreSQL:** strong model/research ecosystem, but adds a separate language boundary for the console.
- **TypeScript and SQLite:** lighter local setup, but would require reevaluating storage semantics when independent workers are added.

## Consequences

The application and console can share typed contracts; runtime validation is still required for all external data. PostgreSQL adds a local service, but supports the transaction and coordination requirements already anticipated. The stack does not guarantee portability across model APIs or durable execution by itself; those remain explicit adapter and runtime responsibilities.

Use real PostgreSQL in integration tests. Preserve module boundaries so changing a model SDK or adopting an execution engine does not require rewriting the console. Pin actual dependency versions only after a compatibility check during implementation.

## Revisit when

A required integration is unavailable in TypeScript, operational constraints prohibit a local database service, or deployment requirements materially change. Existing reference projects are evidence, not a requirement to inherit their whole stacks.
