# Repository guide for coding agents

This project is a Java 21 / Spring Boot harness for durable AI coding runs. The first milestone asks a local Ollama model to implement the Java task-tracker CLI, then checks the result with a host-owned evaluator. A run must continue safely after process crashes and compatible deployments. See [the current handoff](docs/STATUS.md) when resuming milestone work; use the relevant section of [the implementation plan](docs/plans/2026-09-20-local-task-tracker-implementation.md) for task scope and acceptance.

## Where things live

- `src/main/java/`: the harness packages include `workspace/` for immutable source snapshots, `runner/` for bounded Docker build/test work, `benchmark/` for independent evaluation, and `config/` for process roles and infrastructure. Future `model/`, `runs/`, `execution/`, and `api/` packages are assigned in the implementation plan. Do not add a package just because the plan mentions a future task.
- `src/test/java/`: unit tests and `*IT` integration tests. Private good/broken tracker implementations under `benchmark/fixtures/` are test-only.
- `benchmarks/task-tracker-v1/starter/`: the model-visible, deliberately unimplemented CLI. `REQUIREMENTS.md` is its public behavior contract. Never copy private evaluator fixtures or expected answers into the starter, model prompt, or runner image.
- `runner/`: trusted image and fixed command manifests. `src/main/resources/db/migration/`: application Flyway migrations. `compose.yaml`, `config/`, and `scripts/`: local services, pinned images, and setup helpers.
- `docs/specs/`: behavior and security boundaries; `docs/plans/`: delivery order; `docs/development.md`: exact local setup, test commands, and shutdown. ADRs marked **Proposed** are not automatically accepted implementation decisions.

## Build and verification

- Use the committed Gradle wrapper, not Maven or a globally installed Gradle. `./gradlew test` runs unit tests without Docker. `./gradlew build` runs unit and `*IT` integration tests and packages the application; it requires local PostgreSQL, Temporal, a working Docker daemon, and the pinned runner image. See [local development](docs/development.md) before starting those services. Use focused `--tests` filters while iterating, then run checks appropriate to the changed behavior.
- Runtime roles are `api`, `worker`, and `runner`. The API is currently a foundation, not a completed coding-run submission surface; the model loop, durable command delivery, operator API, and live benchmark are later plan tasks. Do not report a scripted or fixture result as live Ollama completion.
- Preserve Gradle dependency locks and checksum verification. Update them only for an intentional dependency change, after checking the new coordinates and hashes against trusted source evidence.

## Git and task workflow

- Work in the primary checkout. Use a worktree only for a real parallel work stream. Before creating a feature branch, fetch `origin`, switch to `main`, update local `main` to `origin/main`, and verify the checkout is clean and both commits match. Do not discard existing work.
- Agree on a plan before implementing code. Keep each task on a focused branch and within its planned scope; add meaningful tests for behavior changes. If a branch drifts, rebase onto updated `main`. Never merge another branch into it; reproduce small abandoned changes or cherry-pick larger ones when necessary.
- Integrate through a PR only. Push the branch and open a PR after verification. Never merge directly into `main`, `master`, `dev`, or `develop`, locally or remotely. Leave PR merging to the user unless explicitly asked. Confirm the PR actually merged before marking a task complete or moving the handoff forward.

## Durability, security, and cleanup

- Treat model source, tool requests, build output, test XML, and candidate stdout as untrusted. Only the host-owned evaluator can set benchmark acceptance. Preserve immutable snapshots, durable receipts, version/digest provenance, and explicit uncertain outcomes across crash recovery; reconcile receipts before retrying a possibly executed mutation.
- Do not commit or print passwords, tokens, private keys, `.secrets/`, or machine-specific selected model profiles. Use local file credentials or references. Review staged files and the PR body for sensitive material.
- Name each new migration for its schema purpose. Verify its filename and Flyway journal tag before committing. Never rename a migration after it is committed, merged, or applied.
- Track any task-owned containers, processes, networks, and temporary services you start. Stop and remove them when no longer needed, including after failures, unless they were deliberately left for user review. Preserve database volumes unless disposal is explicitly requested, and leave unrelated resources alone. `docker compose down` preserves the named database volume; `down -v` does not.
- Before claiming completion, inspect the final diff and Git status and run the relevant checks. Update [docs/STATUS.md](docs/STATUS.md) when the next task or an important handoff changes. Documentation-only edits need link and diff checks, not a full integration build.
