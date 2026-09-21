# Continuous Agent Harness

An environment for running and studying AI agents with local and remote models. Define execution in code, observe each run, and control how work proceeds.

The Java/Spring Boot harness starts with a durable single-agent loop and grows into interactive workflows, graphs, and coordinated fleets. Automatic continuation after crashes and compatible deployments is required in the first usable version. Its operator interface supports four activities:

- **Monitor** progress, outputs, failures, resource usage, and application health.
- **Communicate** with running agents and respond to requests for input.
- **Configure** models, tools, prompts, budgets, and execution limits.
- **Run** tasks with explicit lifecycle controls.

Model hosting and agent runtime are separate choices. A built-in loop can call either a local or remote model; a future runtime adapter can delegate execution to an existing agent system. Capabilities and limitations remain visible in either case.

## Project status

Architecture and delivery planning. The first benchmark is a Java task-tracker CLI built by a local Ollama model, with independent verification and recovery tests. The first implementation slice is operated through an API/CLI; the browser console and remote-provider comparison follow. This repository does not yet contain an executable application. The specifications describe intended behavior; ADRs marked **Proposed** are recommendations for review.

## Documentation

| Document | Purpose |
|---|---|
| [Local coding benchmark](docs/specs/local-task-tracker-benchmark.md) | First target: a Java task-tracker CLI built by a local Ollama model |
| [Local coding implementation plan](docs/plans/2026-09-20-local-task-tracker-implementation.md) | File-level tasks, tests, recovery checks, and local acceptance |
| [Current durable foundation](docs/specs/spring-boot-durable-foundation.md) | Spring Boot, first-version recovery, proposed execution ownership |
| [Architecture](docs/specs/architecture.md) | System boundaries, state, execution, and deployment |
| [Implementation stack](docs/specs/implementation-stack.md) | Recommended technologies, responsibilities, and alternatives |
| [Phase 1 specification](docs/specs/phase-1-observable-loop.md) | First working slice and acceptance criteria |
| [Reference research](docs/specs/reference-research.md) | Findings from existing projects and framework documentation |
| [Roadmap](docs/plans/roadmap.md) | Phases, dependencies, and completion gates |
| [Sprint 01](docs/sprints/01-observable-loop.md) | Proposed first delivery scope |
| [Architectural decisions](docs/adr/README.md) | Major choices, alternatives, and consequences |

`docs/specs/` holds research and specifications; `docs/plans/` holds roadmaps and implementation plans; `docs/sprints/` tracks scoped delivery; `docs/adr/` records significant architectural decisions. Implementation plans follow specification review and stack selection.
