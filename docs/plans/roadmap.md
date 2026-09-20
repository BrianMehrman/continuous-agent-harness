# Delivery roadmap

Status: Proposed

Date: 2026-09-20

Deliver working vertical slices. Each phase has a demonstrable outcome and an evidence-based exit gate; dates and effort estimates follow stack selection and initial implementation. This is a high-level roadmap, not an executable implementation plan.

| Phase | Deliverable | Dependencies | Exit gate |
|---|---|---|---|
| 1 — Observable loop | Local/remote model profiles, bounded loop, verified artifact, persistent history, console, telemetry | Review architecture and phase-one spec; choose stack and benchmark | Phase-one acceptance matrix passes with actual local/remote run evidence |
| 2 — Interactive continuity | Durable commands, messages, questions, safe pause/resume, checkpoints, restart recovery, explicit configuration revisions | Phase 1; durability engine ADR and checkpoint spec | Crash/restart and message redelivery tests preserve state without silently repeating side effects |
| 3 — Coded graphs and workflows | Versioned definitions, sequence, branches, bounded cycles, parallel branches, joins, inspectable execution graph | Phase 2; graph execution spec | Failure, cancellation, restart, and join behavior verified across representative graphs |
| 4 — Fleets and experiments | Delegation, durable task claims, isolated workers, mixed backends, evaluator comparisons | Phase 3; worker ownership and experiment specs | Comparable runs show quality, latency, usage, and intervention with no duplicate task ownership |

## Phase 1 delivery slices

1. Define one fixture-based task and verifier; establish the application skeleton, real store, and scripted adapter around that task.
2. Implement the bounded loop with persistent invocations, outcomes, and limits.
3. Add local and authenticated remote adapter paths and contract tests.
4. Add the operator console, event reconnection, cancellation, and linked retries.
5. Complete traces, metrics, logs, operational failure scenarios, and actual local/remote benchmark evidence.

Instrumentation starts in the first slice; the fifth validates the complete operational experience. Each slice should be reviewable as a pull request. See [Sprint 01](../sprints/01-observable-loop.md).

## Phase 2 design work

Compare LangGraph, Temporal, and extending the bounded executor against concrete recovery scenarios. Choose before implementing a general recovery subsystem. Specify checkpoint compatibility, pending operator messages, uncertain external results, idempotency, lease expiry, and stale-worker fencing. Separate "request accepted" from "request applied" in the console.

## Phase 3 design work

Define code interfaces for nodes and edges; require versioned definitions. Specify how branch outputs combine, how joins handle failures, how cycles consume budgets, and how cancellation propagates. Persist actual execution separately from the static graph. Visual authoring is not part of this phase.

## Phase 4 design work

Start with independent workers and a deterministic task board before adding model-directed supervision. Compare single-agent, worker/evaluator, and supervisor/worker strategies using fixed fixtures and evaluators. Introduce mixed local/remote routing explicitly. Do not silently route local work to remote providers.

Recurring/event-driven operation and checkpoint forks are candidates after continuity is reliable; they require their own specifications and are not hidden commitments in phase 1.

## Decisions before implementation

- Review the recommended TypeScript/PostgreSQL stack in ADR 0005.
- First benchmark fixture and required model/tool capability.
- Initial local endpoint and remote provider for live acceptance.
- Local observability packaging and retention defaults.

The stack recommendation is TypeScript, Fastify, React, and PostgreSQL; ADR 0005 remains proposed for review. There is no need for a separate ADR for routine package selection, endpoint naming, or file organization. Use ADRs when a choice changes execution ownership, persistence semantics, deployment boundaries, or long-term integration options.

## Delivery discipline

Specifications define behavior and acceptance evidence. Implementation plans map the reviewed spec to exact files, interfaces, and test steps once the stack is chosen. Sprint records track scope, progress, and outcomes. Integrate through feature branches and pull requests; never merge directly into the default branch. Use descriptive schema-purpose names for migrations and verify filenames and journal tags before committing.
