# ADR 0001: Begin with a modular application and bounded executor

Status: Proposed

Date: 2026-09-20

## Context

The initial task is to compare observable local and remote agent execution. Distributed scheduling, long waits, and graph recovery are later capabilities. The architecture needs room to adopt an execution engine without taking on its operational requirements prematurely.

## Decision

Start with one deployable application containing an operator API, run store access, and a single execution worker. Implement a small bounded loop behind a runtime boundary. Use transactional durable records from phase 1, but explicitly mark crash-interrupted work rather than claiming automatic recovery.

Keep interfaces and dependencies modular. Before phase 2, make a deliberate decision about adopting a durable runtime. Do not build a general workflow scheduler as an incidental extension of the first loop.

## Alternatives

- **LangGraph immediately:** aligns with future graphs, but commits to orchestration semantics before a representative recovery workflow exists.
- **Temporal immediately:** supplies durable execution, but introduces another service and execution model before the first benchmark needs them.
- **Microservices immediately:** allows independent scaling but adds coordination and deployment complexity without an initial scaling requirement.

## Consequences

The first implementation is easy to run locally and its failure semantics can be tested directly. Some executor internals may be replaced in phase 2. The application owns state consistency and bounded execution in phase 1; it does not promise distributed durability or unlimited runtime.

Language, framework, and database are not selected by this ADR.

## Revisit when

Crash resume, long-lived waits, multiple workers, or graph recovery become implementation requirements. Evaluate those needs against existing engines before expanding the executor.
