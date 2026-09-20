# ADR 0002: Separate model backends from agent runtimes

Status: Proposed

Date: 2026-09-20

## Context

Local and remote inference are foundational requirements. Existing agent products also provide their own loops, tools, sessions, and context handling. Treating a model endpoint and a complete agent runtime as interchangeable would conceal control and compatibility differences.

## Decision

Define separate model and runtime boundaries. Model adapters normalize inference requests and results. Runtime adapters own agent/workflow execution. The built-in runtime invokes configured model adapters and scoped tools; a future external runtime adapter declares which model and tool choices it actually supports.

Model placement, protocol, model identity, credentials, and capabilities are distinct configuration fields. Validate requirements before execution. Missing capabilities produce an explicit error; local work never falls back to a remote provider automatically.

## Alternatives

- **One universal provider interface:** convenient initially, but cannot faithfully represent both inference and whole-agent lifecycle semantics.
- **Bind the harness to one agent SDK:** reduces loop implementation but delegates core experimental variables and local model compatibility to that SDK.
- **Lowest-common-denominator completion API:** portable for text, but hides the capabilities required for tool use and control.

## Consequences

Experiments can vary model hosting independently of workflow code when the selected runtime supports it. Capability checks and adapter tests add work. Some runtime/model combinations are intentionally unsupported. An interface does not make an opaque external runtime observable or resumable beyond its actual guarantees.

## Revisit when

A concrete external runtime integration reveals lifecycle requirements the initial boundary cannot express. Extend the boundary based on evidence rather than implementing speculative adapters now.
