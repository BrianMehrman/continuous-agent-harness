# Continuous Agent Harness

An environment for running and studying AI agents with local and remote models. Define execution in code, observe each run, and control how work proceeds.

The Java/Spring Boot harness starts with a durable single-agent loop and grows into interactive workflows, graphs, and coordinated fleets. Automatic continuation after crashes and compatible deployments is required in the first usable version. Its operator interface supports four activities:

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
export HARNESS_RUNNER_IMAGE="$(sed -n 's/^RUNNER_IMAGE=//p' runner/image.lock)"
./gradlew build
java -jar build/libs/continuous-agent-harness-0.1.0-SNAPSHOT.jar
```

In another terminal, run `curl --fail http://127.0.0.1:8080/actuator/health` and expect `"status":"UP"`. The build runs unit and integration tests; `./gradlew test` runs unit tests without Docker. Stop the API with Ctrl-C, then run `docker compose down` to stop the default Compose project while preserving its database volume. See [local development](docs/development.md) for existing credentials, an isolated test project, troubleshooting, process roles, and configuration.

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
| [Roadmap](docs/plans/roadmap.md) | Phases, dependencies, and completion gates |
| [Sprint 01](docs/sprints/01-observable-loop.md) | Proposed first delivery scope |
| [Architectural decisions](docs/adr/README.md) | Major choices, alternatives, and consequences |

`docs/specs/` holds research and specifications; `docs/plans/` holds roadmaps and implementation plans; `docs/sprints/` tracks scoped delivery; `docs/adr/` records significant architectural decisions. Implementation plans follow specification review and stack selection.
