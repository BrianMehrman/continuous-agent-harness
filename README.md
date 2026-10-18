# Continuous Agent Harness

An environment for running and studying AI agents with local and remote models. Define execution in code, observe each run, and control how work proceeds.

The Java/Spring Boot harness has a local browser console for repository coding tasks. It shows the active task, FIFO queue, recent history, recorded loop events, check receipts, and a downloadable review diff. Proposed changes stay in isolated snapshots until a person applies them. The broader operator vision includes four activities:

- **Monitor** progress, outputs, failures, resource usage, and application health.
- **Communicate** with running agents and respond to requests for input.
- **Configure** models, tools, prompts, budgets, and execution limits.
- **Run** tasks with explicit lifecycle controls.

Model hosting and agent runtime are separate choices. A built-in loop can call either a local or remote model; a future runtime adapter can delegate execution to an existing agent system. Capabilities and limitations remain visible in either case.

## Project status

The first milestone has a local Ollama model implement a Java task-tracker CLI through this harness, with a host-owned evaluator deciding success. See the [current handoff](docs/STATUS.md) for what is merged, what is in progress, and the next task.

## Quick start

Use Java 21, Python 3, and Docker with Compose v2. Run these commands from the repository root. **Before starting**, check [existing database guidance](docs/development.md#choose-a-database) if you have used this checkout before: an existing PostgreSQL volume keeps its passwords even when local secret files change.

For a first-time setup with no credential files or existing database volume:

```sh
python3 scripts/create-local-secrets.py
docker compose up -d --wait postgres temporal
docker compose run --rm --no-deps temporal-namespace
python3 scripts/build-runner-image.py
python3 scripts/build-repository-runner-image.py
export HARNESS_RUNNER_IMAGE="$(sed -n 's/^RUNNER_IMAGE=//p' runner/image.lock)"
export HARNESS_REPOSITORY_RUNNER_IMAGE="$(sed -n 's/^REPOSITORY_RUNNER_IMAGE=//p' runner/repository-image.lock)"
./gradlew bootJar --offline
```

Start the API, worker, and runner in separate terminals using the commands in [local development](docs/development.md#run-the-browser-console). Open <http://127.0.0.1:8080/>, verify an installed Ollama model from the form, and add work. `./gradlew test --offline` runs unit tests; `./gradlew build --offline` also runs integration checks. Stop the three application processes with Ctrl-C, then run `docker compose down` to stop the default Compose project while preserving its database volume.

The specifications describe the broader intended behavior; ADRs marked **Proposed** remain recommendations for review.

## Documentation

| Document | Purpose |
|---|---|
| [Project handoff](docs/STATUS.md) | Completed tasks, next task, and current constraints |
| [Operator API and CLI](docs/api.md) | Run submission, events, cancellation, profile probe, and artifact downloads |
| [Local coding benchmark](docs/specs/local-task-tracker-benchmark.md) | First target: a Java task-tracker CLI built by a local Ollama model |
| [Local coding implementation plan](docs/plans/2026-09-20-local-task-tracker-implementation.md) | File-level tasks, tests, recovery checks, and local acceptance |
| [Current durable foundation](docs/specs/spring-boot-durable-foundation.md) | Spring Boot, first-version recovery, proposed execution ownership |
| [Architecture](docs/specs/architecture.md) | System boundaries, state, execution, and deployment |
| [Implementation stack](docs/specs/implementation-stack.md) | Recommended technologies, responsibilities, and alternatives |
| [Phase 1 specification](docs/specs/phase-1-observable-loop.md) | First working slice and acceptance criteria |
| [Reference research](docs/specs/reference-research.md) | Findings from existing projects and framework documentation |
| [Roadmap](docs/plans/roadmap.md) | Milestones M1–M7 and the sprint table |
| [Sprints](docs/sprints/) | Grouped delivery scope; [Sprint 03](docs/sprints/03-role-profiles-and-remote-provider.md) (M2) is current |
| [Architectural decisions](docs/adr/README.md) | Major choices, alternatives, and consequences |

`docs/specs/` holds research and specifications; `docs/plans/` holds roadmaps and implementation plans; `docs/sprints/` tracks scoped delivery; `docs/adr/` records significant architectural decisions. Implementation plans follow specification review and stack selection.
