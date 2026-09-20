# ADR 0004: Keep diagnostic telemetry independent of execution history

Status: Proposed

Date: 2026-09-20

## Context

The harness needs both an accurate operator history and application diagnostics. Telemetry may be sampled, expire, or fail to export. Model payloads may contain private inputs, and provider usage may be incomplete.

## Decision

Keep authoritative run state and semantic events in the run store. Emit correlated traces, structured logs, and metrics through OpenTelemetry-compatible interfaces. Instrument application requests and storage as well as agent steps, model calls, and tools.

Exporter outages must not corrupt execution state or indefinitely block the worker. Use bounded buffering and visible export-error indicators. Default diagnostic payloads to metadata and identifiers; do not export prompt/tool contents or credentials automatically. Treat unavailable usage as unknown and separate remote price estimates from local resource measurements.

## Alternatives

- **Tracing platform as the run database:** avoids some persistence work, but inherits sampling, retention, and availability constraints inappropriate for execution state.
- **Application logs only:** easy to start, but weak for correlated latency analysis, aggregate health, and operator reconstruction.
- **One vendor-specific observability SDK throughout core logic:** convenient integration, but ties execution code to backend policy and transport.

## Consequences

The operator can inspect a run while telemetry infrastructure is unavailable. Correlation IDs connect the two views. The project maintains a small semantic event schema and a separate telemetry schema. Payload capture and retention require explicit configuration; dashboards cannot recover data that was never captured.

## Revisit when

Remote deployment, multi-user access, payload-based evaluation, or data retention requirements change. Backend selection alone does not require replacing this boundary.
