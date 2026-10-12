# Task 9 review and test-scope reset

Written 2026-10-12 by Claude Code after a code and documentation review requested by the owner. It records where the project stands, what the owner has directed, and the proposed test reduction. Read it before continuing Task 9 or starting Task 10. It does not replace [STATUS.md](../STATUS.md); update that file once the owner confirms the direction below.

## Owner direction

The owner expected a simple harness to build on. After about two weeks, no real model has completed a run through it. The owner's stated criteria:

- "We only need a simple set of tests to make sure the main features are still working."
- Reduce test waste. Do not add crash, race, recovery, or deployment-continuity tests unless the owner asks.

The owner decided later on 2026-10-12 to finish Task 9, keep the existing tests for now, add no new unnecessary tests, and clean up the test suite after Task 9 is done. This review's proposed test deletion is deferred. No decision has been made to remove the durability machinery.

## Where things stand

- `main` is at `d5d77cb` (PR #15, Task 8 merged). Tasks 1-8 are merged.
- Branch `feat/task9-recovery-deployment` has five pushed commits in draft PR #16; unit-test CI passes.
- The working tree also has substantial uncommitted Task 9 work: `RunTelemetry`, `TelemetryOutageIT`, `DurableBoundaryRecoveryIT` and `DurableBoundaryProcess`, `ApiCommandPumpTest`, `RunTelemetryTest`, recovery helpers in `scripts/recovery_test.py`, `config/recovery-test.yaml`, `docs/evidence/task9-recovery.md`, the OpenTelemetry dependency with lockfile and verification-metadata changes, and edits to `ApiCommandPump`, `CommandDispatcher`, `TemporalWorkflowGateway`, `WorkflowGateway`, `RunActivitiesImpl`, `application.yaml`, and several ITs. Do not discard it without the owner's approval.
- `runner/image.lock` is modified with a local image ID. The evidence doc says the image ID is intentionally not committed; revert it before any commit.
- The last integration run (2026-10-12 09:08) failed: `CodingRunRecoveryIT.deadlineExpiresWhileTemporalIsDownWithoutStartingAnotherModelCall` could not restore the previous current deployment version ("missing active task queues"). `restoreCurrentBuild` was edited at 09:09 to pass `--ignore-missing-task-queues`; that fix has not been run.
- The `harness-tryout` Compose project (PostgreSQL and Temporal) was left running.
- No real Ollama model has driven a coding run. A scripted model has driven the full loop (`CodingWorkflowSmokeIT`); Task 5's probe made one real adapter call.

## Why the project feels far from working

The test suite is not padded. A review of the small and configuration-style tests found only about 100 lines that retest libraries or constants and about 30 lines of separate tests that could merge. The volume comes from scope:

- The plan builds bottom-up (Tasks 2-8 each finish a layer before the next exists) and requires tests first, so passing tests became the only definition of done.
- The real model is deferred to Task 10 ("Task 10 alone supplies live model quality evidence").
- About 40% of test lines (~1,860 of 4,725) prove durability guarantees: crash recovery, deployment versioning, command redelivery, lease races, credential scans, and telemetry outage.

Going forward, prefer a walking skeleton: get a real-model run working end to end, then harden only what actually breaks.

## Review findings worth fixing (only if Task 9 code is kept)

These are defects in the code as it is now, not unfinished plan items. Each was checked against the source.

1. `CodingWorkflowImpl.java:79` calls `timedActivities(modelDeadline, 2)`, so Temporal can retry `callModel` on the server. That retry bypasses the workflow's `cancelRequested`, `expired`, and model-attempt budget checks. If the worker dies before the attempt row is written, a fresh model call can start after cancellation or the deadline and is never counted. `a406997` did not close this window.
2. `TemporalWorkflowGateway.cancel` sends a typed update that waits for completion, and `describe()` queries the workflow first. When a run's pinned worker is absent, the API's single dispatch thread blocks, and STARTs for other runs are not delivered. (Likely; observed through the `pendingDelivery` path in `CodingRunRecoveryIT`.)
3. `ApiCommandPump` no longer returns after a failure. During a database or Temporal outage it makes up to 20 slow, failing attempts per tick, and a failure before any row is claimed backs nothing off, so the new code comment is wrong in that case.
4. `TelemetryOutageIT` never exercises the metrics exporter (default 1-minute push step) and uses fixture activities instead of `RunActivitiesImpl`.
5. `ApiWorkflowIT` disables the pump and calls `dispatchOnce` by hand, so no IT covers the scheduled pump delivering an HTTP-accepted START, although its name says it does.
6. Temporal CLI, deployment, and worker-launch helpers are copied across `RecoveryIT`, `DeploymentIT`, and `CodingRunRecoveryIT` and have already drifted (the cause of the 09:08 failure). Their "await alive" helpers sleep for a fixed 10-15 seconds.

Documentation claims that are inaccurate today: `STATUS.md` says the recovery and deployment commands pass (no passing `--all` run exists for the current code) and that PR #16 contains work that is still uncommitted. `operations.md` omits `HARNESS_RUNNER_IMAGE`, which the commands need because `*RecoveryIT` also matches the runner recovery tests. It also calls the log fields "structured" when IDs are only in the message text, and it doesn't mention Spring's default 10% trace sampling.

### Follow-up on 2026-10-12

The review findings above describe the state at review time. Task 9 follow-up changed the model Activity to reconcile its durable receipt before checking the persisted run state and attempt deadline; the worker-death case and the final `recovery_test.py --all` command then passed. The API pump now stops on failures before a row is claimed and tries at most three durably backed-off claimed failures in a tick. `ApiWorkflowIT` uses the scheduled pump and an isolated test schema. The final recovery command passed in 7m 13s and the separate deployment command passed in 43s. `operations.md` now names the runner image variable, the text log format, and default trace sampling.

Finding 2 remains open: a typed cancellation update can occupy the single dispatcher while its pinned worker is absent. The telemetry outage test has a one-second metrics push step, but the observed exporter error can occur during shutdown; it does not prove a collector outage during a real model call. Helper duplication from finding 6 remains for the later test cleanup.

## Test scope: keep, cut, create

Main features: safe file editing, isolated build and test, the evaluator verdict, the Ollama adapter, the coding loop (including limits, cancellation, and deadline), and starting and watching a run through the API/CLI. The result is about 45 tests and 1,500 lines, down from 146 tests and 4,725 lines.

### Keep

| Feature | Keep | Cut from the same file |
|---|---|---|
| File editing | `WorkspacePathPolicyTest` (path rules, size/count limits, symlink escapes). `WorkspaceStoreIT`: `repeatedWriteReturnsOneSnapshotAndKeepsParent`, `rejectsOtherRunsProtectedFilesAndStaleHashes`, `starterIsUnimplementedAndReadsAreDefensive` | `hashesAreOrderedFramedAndContentSensitive` (SHA-256 known-value check); 6 concurrency, quota-transaction, and connection-loss tests |
| Tool routing | `ToolRouterTest`: write, list/read, run_tests, rejected submission, unknown tool, malformed JSON, model-supplied run ID rejected | `evaluatorInfrastructureFailureWithoutArtifactStaysVisible` |
| Build/test in Docker | Move `candidateTestsRunOfflineWithIsolationAndBoundedLogs`, `compileFailureIsAResultWithoutInfrastructureRetry`, and `resourceExhaustionIsContainedInCandidate` from `RunnerRecoveryIT` into `RunnerIT`. Keep `DockerCommandClientTest` | The other 14 `RunnerRecoveryIT` cases; both existing `RunnerIT` admission tests |
| Evaluator | `TaskTrackerEvaluatorIT`: good fixture passes, each broken fixture fails, candidate cannot read evaluator or host paths; the fixtures | `compiledClassWithNoDiscoveredTestsCannotSatisfyAgentTests`, `explicitSubmissionRequirementsAreCheckedSeparately` |
| Ollama adapter | `OllamaAdapterTest`: native tool request returned without execution, stable IDs across calls, malformed response, context overflow. `ProfileProbeTest`: tool round trip verified, cloud/remote model rejected. `ModelConfigurationTest`. `LocalOllamaProbeIT` (opt-in). `ScriptedModelAdapterTest`: fixtures returned in order | 3 adapter, 3 probe, and 2 scripted-adapter edge cases |
| Coding loop | `CodingWorkflowSmokeIT` (the most important regression test). `CodingWorkflowTest`: `failedSubmissionCanBeRepairedWithinTheBudget`, `twoTextOnlyRepliesFailAfterOneProtocolReminder`, `cancellationRequestsRunnerStopBeforeTerminalState`, `lateModelResultCannotDispatchToolsAfterTheRunDeadline` | 7 retry, uncertainty, and admission-race cases |
| API/CLI | `ApiWorkflowIT` (after the fix below). `RunApiIT`: `duplicateStartOrderedEventsAndVersionedCancel`, `runScopedArtifactAndMalformedInputs`. `ApiSecurityTest`. `scripts/test_harness.py`, `scripts/test_local_secrets.py` | 2 projection-staleness tests in `RunApiIT` |
| Startup config | `RoleConfigurationTest`: `rejectsInvalidRoleSelection`, plus the missing and empty password cases merged into one parameterized test | The other 6 Spring wiring tests |

### Cut entirely

- `recovery/`: `CodingRunRecoveryIT`, `DeploymentIT`, `RecoveryIT`, `DurableBoundaryRecoveryIT`, `SecretRedactionIT`, and their helpers (`DurableBoundaryProcess`, `CodingRecoveryWorkerProcess`, `DeploymentVersionWorkerProcess`, `DeploymentProbeWorkflow`, `DeploymentProbeWorkflowImpl`, `RecoveryModelConfiguration`, `RunnerRecoveryProcess`).
- Durability ITs: `CommandDeliveryIT`, `RunCommandServiceIT`, `RunActivitiesImplIT`, `ModelAttemptStoreIT`, `RunAdmissionStoreIT`, `EvaluationClassificationIT`, `EvaluationRuntimeIT`, `EvaluationRuntimeRecoveryIT`.
- Wiring and duplicate coverage: `InfrastructureIT`, `BlobAndProfileStoreIT`, `TemporalWorkflowGatewayTest`, `InvocationHasherTest`, `ProfileControllerTest`, `ApiCommandPumpTest`.
- Telemetry: `TelemetryOutageIT`, `RunTelemetryTest`.
- Scripts and config: `scripts/recovery_test.py`, `scripts/deployment_test.py`, `scripts/runner_fault_test.py`, `config/recovery-test.yaml`.

Before deleting, check each helper's references (for example, `UnversionedCodingWorkflow` and `CodingWorkflowSmokeIT.FixtureActivities` are shared by kept tests).

### Create

1. An opt-in live end-to-end smoke test: submit one task-tracker run to a local Ollama model through the CLI and assert it reaches a terminal state with an evaluator verdict (success not required). This is the only test that answers whether the harness works, and it does not exist yet.
2. A run-limit test: nothing tests `LIMIT_EXHAUSTED` today. Add one case to `CodingWorkflowTest`.
3. Fix `ApiWorkflowIT` so it goes through the real `ApiCommandPump` instead of calling `dispatchOnce` directly.

## Rule changes needed first

The test volume follows from instructions, and they will regenerate it unless changed:

- `AGENTS.md` says "add meaningful tests for behavior changes." Add: "Tests cover main features only. Do not add crash, race, recovery, or deployment tests unless the owner asks."
- The implementation plan's header requires a test-first workflow, and Task 9 requires process-level crash tests. Mark Task 9 deferred and Task 10's live run as the next priority.

## Proposed order for the later test cleanup

1. Agree on the open decisions above.
2. On a new branch from `main` (following `AGENTS.md` Git rules), update `AGENTS.md` and the plan.
3. Run one real Ollama task-tracker run through the API/CLI and record what breaks. Ask the owner which installed model tag to use; do not pull models.
4. Apply the test reduction and the three new tests; run `./gradlew test` and the remaining ITs.
5. Update `STATUS.md`, stop task-owned containers, and open a PR. Do not merge it.
