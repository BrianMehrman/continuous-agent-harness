# Implementation stack

Status: Java/Spring Boot accepted; supporting libraries proposed

Date: 2026-09-20

Decisions: [ADR 0006](../adr/0006-java-spring-boot-foundation.md), [proposed ADR 0007](../adr/0007-durable-execution-foundation.md)

## First implementation milestone

The accepted target is a [task-tracker CLI built by local Ollama](local-task-tracker-benchmark.md). The [implementation plan](../plans/2026-09-20-local-task-tracker-implementation.md) proposes Java 21, Spring Boot 4.1.1, Spring AI 2.0.1 and Temporal Java SDK 1.36.1/server 1.32.0, with dependency/image resolution and compatibility tests as its first task. These versions are documentation-checked candidates, not a tested application build. A trusted container runner and immutable workspace storage are required for coding tools.

## Foundation

Use Java and Spring Boot for the harness. Automatic continuation after crashes and deployments is required in the first usable version. See the [durable foundation design](spring-boot-durable-foundation.md) for execution ownership and recovery requirements.

| Area | Choice | Status / responsibility |
|---|---|---|
| Backend | Java, Spring Boot | Accepted; API and worker application |
| Model integration | Spring AI behind a model adapter | Proposed; local/remote inference with explicit capabilities; tool scheduling stays with the Workflow |
| Execution | Temporal Java SDK and service | Proposed; authoritative durable workflow execution |
| Application storage | PostgreSQL | Proposed; configuration, command delivery, artifacts, query projections |
| Schema migrations | Flyway SQL migrations | Proposed; descriptive names such as `V1__create_model_profiles.sql`; verify history entries |
| Operator console | React, TypeScript, Vite | Proposed; generated API contracts and runtime validation |
| Java build | Gradle wrapper with Groovy DSL | Accepted; pinned distribution, Java 21 toolchain, and dependency locking |
| Tests | JUnit, Spring Boot Test, Testcontainers, Temporal test tooling | Proposed; real-service crash/deployment tests in addition to unit tests |
| Browser tests | Playwright | Proposed; start, reconnect, cancellation, recovery visibility |
| Diagnostics | Spring observability, OpenTelemetry-compatible export, structured logs, Grafana LGTM | Proposed; bounded export and correlated identifiers |
| Local services | Docker Compose | Proposed; PostgreSQL, durable Temporal service, optional diagnostics |

Choose exact supported versions after compatibility verification; pin dependencies and images. Temporal service persistence and application tables have separate ownership. A disposable development server does not establish the required restart durability.

## Integration rules

Use lower-level model requests so each model invocation and tool execution has an explicit Activity boundary. Do not let an opaque framework loop hide tool effects or retries from durable orchestration. Disable automatic framework tool execution on that path and test that no tool executes implicitly.

Default configuration changes affect only future runs. Preserve run definition/profile revisions across deployments. Resolve secret references only outside Workflow code and prevent values from entering durable history or diagnostics.

Generate browser contracts from the API schema. Java/TypeScript language separation is acceptable and does not require two execution authorities.

Bind operator access to loopback with same-origin mutation checks and CSRF protection. Keep diagnostic buffers bounded and payload capture off by default. Retain the proposed seven-day local application payload retention default with explicit cleanup; retention must never remove data required by active runs. Temporal history retention is separate and needs an explicit operational policy.

## Alternatives

LangChain4j remains a viable model abstraction, with Java-oriented AI Services and model integrations. Prefer one model framework initially; Spring AI is recommended for alignment with Spring Boot and its observability. Neither framework alone establishes the required recovery semantics.

Go and Rust are viable backend alternatives, but Java/Spring Boot is selected. The earlier TypeScript recommendation emphasized shared UI contracts and is superseded; no performance comparison has been measured.

## Sources

- [Spring AI overview](https://docs.spring.io/spring-ai/reference/)
- [Spring AI tools](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Spring AI observability](https://docs.spring.io/spring-ai/reference/observability/)
- [LangChain4j introduction](https://docs.langchain4j.dev/intro/)
- [Temporal Java SDK](https://github.com/temporalio/sdk-java)
