# ADR 0006: Use Java and Spring Boot for the harness

Status: Accepted

Date: 2026-09-20

Supersedes: [ADR 0005](0005-typescript-postgresql-stack.md) as the application stack baseline

## Context

The harness is a long-running execution service as well as an operator application. Automatic continuation after crashes and deployments is required in the first usable version. The initial TypeScript proposal prioritized sharing a language with the browser and did not adequately compare Java, Go, and Rust.

## Decision

Use Java and Spring Boot for the harness backend. Keep model hosting independent of the application language. Recovery across crashes and deployments is a first-version requirement, not a phase-two enhancement.

Spring Boot does not itself provide durable workflow execution. Spring AI for model integration and Temporal for execution are supporting recommendations, recorded separately in [ADR 0007](0007-durable-execution-foundation.md) and the [revised design](../specs/spring-boot-durable-foundation.md). Their selection is not implied by acceptance of this ADR. React and PostgreSQL remain proposed supporting choices.

## Alternatives

- **TypeScript/Node:** convenient shared UI contracts and experimentation; this convenience is insufficient to determine the backend foundation.
- **Go:** a strong alternative for concurrent workers and straightforward deployment. Java/Spring Boot is the selected application ecosystem; no benchmark establishes Go as inferior.
- **Rust:** strong memory safety and resource control, with additional ownership/async design work for an evolving orchestration application. No measured resource constraint currently requires it.

## Consequences

The console and backend have a language boundary; generate client contracts from a reviewed API schema and validate input at runtime. Evaluate actual dependency compatibility before pinning versions. Do not claim that choosing Java makes model inference faster or grants crash recovery.

The former Node/Fastify/Drizzle/Vitest backend proposal is superseded. No application migration is needed because implementation has not started.

## Revisit when

Measured resource requirements or essential integrations conflict with the Java foundation. Evaluate evidence before changing languages.
