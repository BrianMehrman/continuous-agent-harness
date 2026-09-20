# Continuous Agent Harness

An environment for running and studying AI agents with local and remote models. Define execution in code, observe each run, and control how work proceeds.

The harness grows from a single agent loop into resumable workflows, graphs, and coordinated fleets. Its operator interface supports four activities:

- **Monitor** progress, outputs, failures, resource usage, and application health.
- **Communicate** with running agents and respond to requests for input.
- **Configure** models, tools, prompts, budgets, and execution limits.
- **Run** tasks with explicit lifecycle controls.

Model hosting and agent runtime are separate choices. A built-in loop can call either a local or remote model; a future runtime adapter can delegate execution to an existing agent system. Capabilities and limitations remain visible in either case.

## Project status

Architecture and delivery planning. This repository does not yet contain an executable application. The specifications describe intended behavior; ADRs marked **Proposed** are recommendations for review.

## Documentation

| Document | Purpose |
|---|---|
| [Architecture](docs/specs/architecture.md) | System boundaries, state, execution, and deployment |
| [Implementation stack](docs/specs/implementation-stack.md) | Recommended technologies, responsibilities, and alternatives |
| [Phase 1 specification](docs/specs/phase-1-observable-loop.md) | First working slice and acceptance criteria |
| [Reference research](docs/specs/reference-research.md) | Findings from existing projects and framework documentation |
| [Roadmap](docs/plans/roadmap.md) | Phases, dependencies, and completion gates |
| [Sprint 01](docs/sprints/01-observable-loop.md) | Proposed first delivery scope |
| [Architectural decisions](docs/adr/README.md) | Major choices, alternatives, and consequences |

`docs/specs/` holds research and specifications; `docs/plans/` holds roadmaps and implementation plans; `docs/sprints/` tracks scoped delivery; `docs/adr/` records significant architectural decisions. Implementation plans follow specification review and stack selection.
