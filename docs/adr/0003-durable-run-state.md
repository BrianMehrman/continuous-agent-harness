# ADR 0003: Persist run state and scoped configuration independently of conversations

Status: Proposed

Date: 2026-09-20

## Context

Runs must remain inspectable after a browser disconnects or an application restarts. Default changes must not silently alter work already underway. Conversation transcripts, external session IDs, and in-memory queues do not provide these guarantees on their own.

## Decision

Make the run the authoritative execution unit. Persist current state and ordered semantic events transactionally. Pin the definition and effective configuration at creation. Store secret references rather than secret values. Separate operator command acceptance from application by the worker.

Default changes apply to future runs. Active-run changes require explicit commands and new recorded configuration revisions at safe boundaries. Phase 1 keeps active configuration immutable; phase 2 introduces supported mutations and checkpoints.

Use a relational store with uniqueness and optimistic concurrency. Do not require full event sourcing. A retry in phase 1 creates a linked new run; recorded history does not imply executable crash recovery.

## Alternatives

- **Conversation as execution state:** couples lifecycle to interaction and leaves orchestration state implicit.
- **Current defaults read on each step:** easy to implement, but changes experimental conditions without a clear historical boundary.
- **In-memory execution plus telemetry:** cannot reliably reconstruct state after process loss or telemetry sampling.
- **Full event sourcing:** offers reconstruction flexibility, but adds event migration and projection obligations beyond current needs.

## Consequences

Runs are explainable and can survive process loss as records. Transactions and version checks are required. Checkpoints, command redelivery, and side-effect reconciliation still need explicit phase-two design. Exactly-once external effects are not promised.

## Revisit when

A durable engine is selected. Establish one owner for execution state and define how its history maps into the operator view; avoid two independent authorities.
