# Recommended implementation stack

Status: Proposed

Date: 2026-09-20

Decision: [ADR 0005](../adr/0005-typescript-postgresql-stack.md)

## Recommendation

Build a TypeScript modular application with a React console and PostgreSQL storage. This keeps the operator interface, model integrations, and execution logic in one language while preserving a relational foundation for later worker coordination. Start with one repository and one package rather than an internal package publishing system.

| Area | Choice | Responsibility |
|---|---|---|
| Runtime | Node.js, supported LTS release at implementation time | Application and worker process |
| Language | TypeScript with strict checking and ESM | Shared contracts and explicit adapter types |
| HTTP | Fastify | API, validation, health endpoints, event streaming, serving built UI |
| Console | React and Vite | Run list/detail, configuration selection, lifecycle controls |
| Storage | PostgreSQL | Runs, attempts, events, profile revisions, commands, small artifacts |
| Database access | Drizzle and pg | Typed queries, explicit transactions, reviewed SQL migrations |
| Contract validation | JSON Schema at external boundaries | Validate task input, model/tool outputs, API requests |
| Model integration | Small protocol adapters using fetch or an SDK behind the adapter | Local/remote inference with deadlines and explicit capability mapping |
| Testing | Vitest and Playwright | Behavior/contract tests and operator flows |
| Diagnostics | OpenTelemetry traces/metrics and structured Pino logs | Correlated application and agent signals |
| Local diagnostics backend | Grafana Docker LGTM | Local collection and inspection of traces, metrics, and logs |
| Dependency environment | Docker Compose | PostgreSQL and optional diagnostics backend |

Framework/library choices within this table are implementation recommendations, not separate ADR subjects. Verify compatibility and pin versions in the lockfile and container configuration during implementation; do not use floating image tags in reproducible acceptance evidence.

## Development and deployment shape

Run PostgreSQL and optional telemetry services through Compose. Run the Node application locally with watch mode. The application hosts the API and worker; Vite may run as a separate development process with same-origin proxying. A production build is served by the Node application. This is one deployment unit, not a Next.js/serverless application with request-bound execution.

Use a dedicated database connection to hold the phase-one singleton advisory lock. Do not return that connection to a general query pool. If the connection is lost, stop accepting work and stop execution rather than silently proceeding without ownership. Broader lease/fencing recovery is a phase-two concern.

Keep telemetry export asynchronous and bounded. Use structured logs even with export disabled. The local LGTM environment is for development; it does not establish a production monitoring or retention strategy. Set a seven-day default for local run payload retention, preserve the setting in project configuration, and require explicit cleanup execution in phase 1. Do not auto-delete records during an active experiment. Payload retention policy and diagnostic backend retention are distinct.

## Suggested module layout

```text
src/
  server/          HTTP routes and application startup
  runs/            Lifecycle, commands, persistence operations
  execution/       Worker and built-in bounded loop
  models/          Profile resolution and model adapters
  tools/           Tool contracts and scoped execution
  telemetry/       Traces, metrics, log correlation and redaction
  definitions/     Code-defined tasks and deterministic verifiers
  web/             Operator console
  db/              Schema and connection management
migrations/        Reviewed SQL with descriptive purpose names
fixtures/          Immutable non-private benchmark inputs
config/            Example model profiles without secrets
tests/            Integration, adapter-contract and end-to-end tests
```

The layout is a planning baseline, not a mandate for empty scaffolding. Create modules with the first tested behavior that needs them. Unit tests may be colocated with their modules. Use descriptive migration names such as `create_run_history`; verify generated filenames and journal tags before committing. Do not rename a migration after it has been committed or applied.

## Alternatives considered

**Rails:** well suited to the operator application, background jobs, and persistence, with an existing observability reference available. It remains a strong choice if product CRUD dominates. TypeScript is recommended here because the console and evolving coded execution definitions can share a language and contracts, and the inspected remote-agent example is already in TypeScript.

**Python with FastAPI:** a good fit for research-heavy graph and model tooling. It would introduce a separate UI language and associated contract generation or duplication. Reconsider if Python-only integrations become core requirements.

**SQLite:** minimizes local dependencies and would suffice for one worker. PostgreSQL is recommended because coordination and independent workers are explicit later goals; it avoids changing database behavior when those phases begin. The tradeoff is operating a local database service from the start.

**LangGraph or Temporal immediately:** either could own execution, but stack choice does not settle that decision. Evaluate durability requirements before phase 2. The application model must not become a second authority for engine-owned execution state.

## Sources

- [Fastify documentation](https://fastify.dev/docs/latest/) — HTTP framework, validation, and TypeScript documentation.
- [PostgreSQL explicit locking](https://www.postgresql.org/docs/current/explicit-locking.html) — transactional and advisory locking facilities.
- [Drizzle migrations](https://orm.drizzle.team/docs/migrations) — schema/migration workflows.
- [OpenTelemetry JavaScript](https://opentelemetry.io/docs/languages/js/) — signal support and SDK instrumentation. Verify signal/exporter maturity for the chosen versions; JSON log collection need not depend on an experimental log SDK.
- [Grafana Docker LGTM](https://grafana.com/docs/opentelemetry/docker-lgtm/) — local development observability environment.
