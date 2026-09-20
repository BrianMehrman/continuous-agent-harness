# Architectural decision records

ADRs capture choices that materially affect system boundaries, execution semantics, operational requirements, or the cost of future change. Routine implementation details belong in specifications and plans.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-modular-application.md) | Begin with a modular application and bounded executor | Historical; revision proposed in 0007 |
| [0002](0002-model-runtime-boundaries.md) | Separate model backends from agent runtimes | Proposed |
| [0003](0003-durable-run-state.md) | Persist run state and scoped configuration independently of conversations | Historical; revision proposed in 0007 |
| [0004](0004-observability-boundary.md) | Keep diagnostic telemetry independent of execution history | Proposed |
| [0005](0005-typescript-postgresql-stack.md) | Use TypeScript and PostgreSQL as the application foundation | Superseded by 0006 |
| [0006](0006-java-spring-boot-foundation.md) | Use Java and Spring Boot; require recovery from phase one | Accepted |
| [0007](0007-durable-execution-foundation.md) | Use Temporal for durable execution from phase one | Proposed |

A proposed record describes a recommendation for review. Acceptance establishes the architectural baseline; a later reversal supersedes the record rather than erasing its rationale. Record date, context, decision, alternatives, consequences, and conditions for revisiting. No separate ADR is needed merely because a detail can be configured.
