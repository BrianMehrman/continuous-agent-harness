# Phase 1: Observable agent loop

Status: Draft for review

Date: 2026-09-20

Depends on: [Architecture](architecture.md)

## Outcome

Run the same bounded task against a local model and a remote model, inspect what happened, and evaluate the result using a deterministic verifier. Phase 1 establishes measurement and automatic continuation after crashes and compatible deployments. The [durable foundation design](spring-boot-durable-foundation.md) governs recovery, command delivery, execution ownership, and deployment semantics; its P1-16 through P1-19 criteria are part of this specification.

## Proposed demonstration task

Provide a small immutable fixture of support incidents with identifiers, timestamps, service names, severity, and resolution state. The agent can list fixture files, read a fixture, and use a pure calculation tool. It produces a structured report containing incident counts by service and severity, unresolved incident IDs, and the source fixture identifier.

The verifier computes the expected values directly from the fixture. Model prose does not determine success. Fixtures contain no private data and tools cannot access arbitrary filesystem paths. This exercise covers multiple model/tool turns, artifact output, and an independently checkable result without coding-agent side effects.

The task is a proposed first benchmark; its selection does not restrict later coding or research workflows.

## Required behavior

| ID | Requirement | Acceptance evidence |
|---|---|---|
| P1-01 | Configure named local and remote model profiles with endpoint, model, credential reference, and capabilities | Both profiles execute the same definition without editing workflow code |
| P1-02 | Validate tool capability and input before queuing | Unsupported profile and malformed input produce clear errors and no model call |
| P1-03 | Freeze effective configuration and definition identity for a run | Changing defaults leaves a queued/running run unchanged |
| P1-04 | Execute a bounded model/tool loop | Scripted multi-turn response invokes only validated tools and produces a final artifact |
| P1-05 | Enforce call count, per-call timeout, and run deadline | Boundary tests show no new invocation starts after a limit; outcome records the stop reason |
| P1-06 | Persist state, invocations, artifact, and ordered events | Browser reconnect reconstructs history and status from storage |
| P1-07 | Cancel work | Queued run cancels immediately; running cancellation prevents subsequent calls and cannot be overwritten by late success |
| P1-08 | Expose application and agent observability | A run correlates HTTP, execution, model, and tool spans with structured logs and metrics |
| P1-09 | Preserve truthful usage | Missing provider usage displays unknown; no invented cost or token count |
| P1-10 | Verify success independently | Incorrect structured output fails; correct output passes even when prose differs |
| P1-11 | Handle application restart honestly | The same run resumes using recorded completed steps; pending work remains eligible and prior history persists |
| P1-12 | Keep secrets out of displayed/exported configuration and diagnostics | Automated checks using sentinel credentials find no leakage |
| P1-13 | Remain operable during telemetry outage | Run completes and history remains inspectable with exporter unavailable |
| P1-14 | Prevent duplicate creation and conflicting updates | Repeated start command ID returns the same run; incompatible state version rejects a mutation |
| P1-15 | Provide a minimal operator console | Start form, profile selection, run list, run detail, event feed, artifact/result, cancel and linked retry |

## Execution rules

Start with one active run at a time and sequential tool execution. Default limits for the demonstration: 12 model calls, 24 tool calls, 120 seconds per model call, 10 seconds per tool, and 10 minutes total wall time. Limits are configurable before starting. The deadline includes tool/model time and queue time is displayed separately; execution deadline starts on claim.

Before every call, validate the remaining allowance. Provider output-token limits are sent when supported. Token/cost budgets are optional and must not be advertised as hard guarantees without enforceable usage and pricing. Phase 1 always enforces call and time bounds.

Use explicit bounded infrastructure retry/reconciliation policies as defined in the durable foundation design. Recorded completed calls are not repeated on replay; uncertain completion can cause another attempt and must remain visible. Do not retry invalid output or verifier failures automatically. Record timeout, transport failure, provider rejection, malformed response, invalid tool request, verification failure, and limit exhaustion distinctly. A manually retried run references its predecessor but starts from the original input with a newly selected, recorded configuration.

The runtime retains structured model messages and tool results for this bounded task. It rejects context overflow rather than silently truncating. Streaming text is optional; semantic progress events and final content are required. Providers without token streaming remain usable.

## Operator contract

The console is a client of an application API. Logical operations are create/list/read run, read ordered events after a sequence, cancel run, retry run, and list available profile revisions. Server-sent events are the proposed live transport; polling remains a valid fallback and neither owns execution.

Repeated command IDs are idempotent within the command's scope. A durably accepted cancellation is shown as pending delivery until the Workflow applies it as cancel_requested; final acknowledgment follows when execution stops. P1-07 ordering is determined by Workflow application, and P1-14 expected-version checks use authoritative workflow state, not a potentially stale projection. Unsupported pause/resume and live messaging controls are not shown as available in phase 1.

Profile settings are configured locally in phase 1 and shown in the console without secret values. Full profile editing, active-run messages, and revisions applied to live runs belong to phase 2.

## Test strategy

- Unit tests with a scripted model adapter prove loop sequencing, argument validation, result verification, limits, and terminal-state races.
- Adapter contract tests against a local HTTP fixture server prove request construction, authentication headers, error normalization, missing usage, timeouts, and tool-call normalization. They do not require paid inference.
- Database integration tests prove atomic history/state updates, idempotent start, durable reconnect, and process restart behavior using the selected real database engine.
- One browser/API flow proves start → progress → verified result, plus cancellation and reconnect.
- Opt-in live acceptance runs exercise one actual local endpoint and one authenticated remote endpoint. Record exact model/profile versions, verifier result, latency, and reported usage. The infrastructure tests must pass even when a live model gives an incorrect answer; report that as benchmark failure.
- Observability acceptance includes normal, slow, failed-provider, and recovered-provider scenarios, plus exporter failure and sentinel-secret checks.

Do not declare local/remote parity based only on mocks. Distinguish supported adapter behavior from model task quality.

## Exit gate

All P1 acceptance criteria have evidence; the local and remote demonstration runs are inspectable; verifier outcomes and usage limitations are reported; startup/operation instructions reproduce the environment. A failed benchmark must be investigated or explicitly reported, not disguised as harness success.

## Exclusions

Checkpoint forking, remote control channels, arbitrary shell execution, graph scheduling, multi-agent delegation, recurring triggers, automatic prompt optimization, and multi-user hosting.
