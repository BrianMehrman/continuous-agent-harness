# Local Task-Tracker Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Have a local model implement a verified Java task-tracker CLI, with recoverable source edits, bounded isolated build/test execution, and continuation after worker crashes and compatible deployments.

**Architecture:** Spring Boot hosts the operator API and application services; Temporal owns the bounded coding Workflow. PostgreSQL stores immutable workspace snapshots, model/tool receipts, command delivery records, and an operator projection; an independently running trusted runner supervises disposable Docker containers. Spring AI performs one local Ollama request per model Activity, while an external evaluator determines success.

**Tech Stack:** Java 21, Gradle 9.8.0, Spring Boot 4.1.1, Spring AI 2.0.1, Temporal Java SDK 1.36.1 / server 1.32.0, PostgreSQL, Flyway, JUnit, Docker, Ollama.

**Spec:** [Local task-tracker benchmark](../specs/local-task-tracker-benchmark.md), plus [durable foundation](../specs/spring-boot-durable-foundation.md) where not superseded.

**Status:** Task 1 foundation implemented; subsequent tasks remain planned. Gradle supersedes the original Maven selection; see [migration plan](2026-09-26-gradle-migration.md) and [development evidence](../development.md). Benchmark and local-first scope are accepted. Spring AI and Temporal remain proposed supporting choices in ADR 0007. Acceptance of this implementation plan would establish those implementation choices; do not relabel the ADR before that review.

## Global constraints

- Java/Spring Boot is the accepted backend. Implement the task tracker in Java 21 with a trusted, immutable Gradle build.
- Local inference is the first live integration. No automatic remote fallback, cloud-backed Ollama models, or automatic model pulls.
- Coding-profile defaults: 40 logical model turns, 100 tool calls, 180 seconds per model attempt, 120 seconds per build/test command, 180 seconds per evaluation, 10 seconds per file operation, 30 minutes total after execution starts.
- At most two infrastructure model attempts per logical turn, at most two runner attempts per logical invocation, and at most 80 dispatched model attempts per run. Disable nested SDK retries.
- Source limits: 1 MiB total, 64 KiB per file, 64 files. Captured output: 256 KiB per invocation with explicit truncation metadata.
- Runner limits: 2 CPU, 1 GiB memory, 128 processes, no network, non-root, read-only root, bounded scratch, no host home/source/credentials/socket mounts.
- Writable paths: `src/main/java/**/*.java`, `src/test/java/**/*.java`, `README.md`. No arbitrary shell tool, mutable build file, or model-accessible evaluator.
- The absolute execution deadline continues through downtime. Success requires evaluator evidence for the current immutable snapshot.
- Temporal owns lifecycle state; PostgreSQL projections cannot independently advance execution or authorize cancellation.
- Name migrations for schema purpose. Verify filename and Flyway history description before committing. Integrate only through feature branches and PRs.
- Preserve active-run source, conversation, receipts and artifacts regardless of cleanup policy. No automatic active-run garbage collection.

## Scope and ordering

This plan delivers an API/CLI-operated local coding milestone, not the full eventual product. It includes cancellation, queryable history, basic telemetry, immutable configuration, and crash/deployment evidence. React controls and remote-provider parity get separate plans after this slice. Do not claim P1-15 or remote P1-01 complete from this work.

Execute tasks sequentially in this dependency order: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10. Each task has a focused test cycle and a commit boundary. Tasks 2–4 build the trusted code-execution substrate before granting it to a model. Task 7 uses a scripted model first. Task 10 alone supplies live model quality evidence. Do not have this coding session implement the benchmark solution in the agent's starter.

## Compatibility and inspected environment

Planning checks on 2026-09-20 found Java 21.0.2, Maven 3.9.16, Docker client/server 29.6.2, and a reachable Ollama installation. The operator confirmed Ollama for the first live integration. Installed models include `qwen3.5:9b`, `qwen3.6:27b`, `qwen3.8:latest`, and cloud-tagged entries. No installed model has yet passed this harness's native-tool probe or coding benchmark. Set `HARNESS_OLLAMA_MODEL` explicitly; model selection does not block scripted development. Do not silently pick a cloud entry or treat `latest` as immutable.

The documentation lists Spring Boot 4.1.1 as compatible with Java 21 and Spring AI 2.0.x as compatible with Boot 4.1.x. The chosen Temporal server release references Java SDK 1.36.1. These establish a candidate dependency baseline, not a tested build. Task 1 must resolve and smoke-test the exact combination, including payload serialization; if it fails, revise the explicit versions and record the evidence before continuing. Use direct Temporal client/worker beans initially, avoiding an unverified Boot auto-configuration dependency.

Resolve OCI image digests for the local architecture during Task 1 and commit them to `config/images.lock`; never put invented digests in this plan. The JDK image must use a maintained Java 21 patch, not the inspected host's older patch merely for convenience. Pin PostgreSQL 17's current patch image and retain its digest in the lock. No runtime dependency pulls occur during benchmark execution. Pin the target project's JUnit version from the resolved Boot BOM and Gradle wrapper from the tested target build; commit the trusted build/settings files, wrapper, dependency lockfile, and effective dependency list.

Primary references checked during planning:

- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring AI compatibility](https://docs.spring.io/spring-ai/reference/getting-started.html)
- [Spring AI low-level tool execution boundary](https://docs.spring.io/spring-ai/reference/api/tools.html#_chatmodel_tool_calling)
- [Ollama model integration](https://docs.spring.io/spring-ai/reference/api/chat/ollama-chat.html)
- [Temporal server 1.32.0](https://github.com/temporalio/temporal/releases/tag/v1.32.0)
- [Temporal Java SDK 1.36.1](https://github.com/temporalio/sdk-java/releases/tag/v1.36.1)
- [Current Worker Deployment versioning](https://docs.temporal.io/production-deployment/worker-deployments/worker-versioning)

## File map and shared contracts

Use one Gradle application with package root `com.brianmehrman.harness`. Package by responsibility. `src/main/java/com/brianmehrman/harness/` is abbreviated `J/` below, and `src/test/java/com/brianmehrman/harness/` is `T/`. These abbreviations always expand to those paths; they are not actual directories. Keep each public Java type in its matching file.

| Package / directory | Responsibility |
|---|---|
| `J/benchmark/` | Immutable task definition and trusted evaluator controller |
| `J/workspace/` | Snapshot validation, hashing, idempotent edits |
| `J/runner/` | Durable container invocation ledger and supervisor |
| `J/model/` | Normalized model contract, scripted adapter, Ollama adapter |
| `J/execution/` | Temporal interfaces, deterministic Workflow, Activity implementations |
| `J/runs/` | Start/cancel command acceptance, outbox delivery, event projection |
| `J/api/` | Loopback HTTP endpoints and input validation |
| `J/config/` | Database, Temporal, model profiles, role-specific startup |
| `src/main/resources/db/migration/` | Reviewed application SQL migrations |
| `benchmarks/task-tracker-v1/starter/` | Only files visible to the coding agent |
| `T/benchmark/fixtures/` | Private good/broken implementation fixtures for evaluator tests |
| `runner/` | Trusted runner image, entrypoint and fixed command manifest |
| `scripts/` | Operator and process-recovery test clients, using Python standard library |
| `docs/evidence/` | Versioned acceptance summaries and artifact references, without credentials |

Introduce the following values in their owner packages as tasks need them; preserve these names across implementations. Records are serialization contracts, not Spring persistence entities. Use primitive/string fields and explicit constructors validated at boundaries, not SDK-specific response objects in Workflow history.

```java
// workspace/SnapshotRef.java
public record SnapshotRef(String sha256) {}
// workspace/WriteRequest.java: "absent" means a new file is expected
public record WriteRequest(String runId, String invocationId, SnapshotRef parent,
                           String path, String expectedSha256, String content) {}
// workspace/WorkspaceStore.java
public interface WorkspaceStore {
    SnapshotRef seed(String runId, String benchmarkVersion);
    java.util.SortedMap<String, String> files(String runId, SnapshotRef ref);
    SnapshotRef write(WriteRequest request);
}
// runner/Operation.java
public enum Operation { BUILD, TEST, EVALUATE }
// runner/InvocationRequest.java
public record InvocationRequest(String runId, String invocationId,
    SnapshotRef snapshot, Operation operation, long deadlineEpochMillis) {}
// runner/InvocationResult.java: terminal values only; unknown evidence is explicit
public record InvocationResult(String invocationId, String inputSha256,
    String status, Integer exitCode, String logBlobId, boolean truncated,
    String artifactBlobId, int attempt, boolean uncertain) {}
// runner/Runner.java
public interface Runner {
    void ensureStarted(InvocationRequest request);
    java.util.Optional<InvocationResult> result(String invocationId);
    void cancel(String invocationId);
}
// benchmark/Evaluation.java
public record Evaluation(boolean passed, String snapshotSha256,
    String artifactSha256, String evaluatorVersion, long seed,
    java.util.List<String> failedCases, int discoveredAgentTests) {}
// benchmark/Evaluator.java
public interface Evaluator {
    Evaluation evaluate(String runId, String invocationId, SnapshotRef snapshot,
                        long seed, long deadlineEpochMillis);
}
// model/ModelCall.java: conversation is a durable blob reference
public record ModelCall(String runId, String invocationId, int attempt,
    String profileRevision, String conversationBlobId, long deadlineEpochMillis) {}
// model/ToolRequest.java: provider correlation ID is not the ledger ID
public record ToolRequest(String providerCallId, String name, String argumentsJson) {}
// model/ModelReply.java
public record ModelReply(String text, java.util.List<ToolRequest> tools,
    Long inputTokens, Long outputTokens, String finishReason) {}
// model/ModelAdapter.java
public interface ModelAdapter { ModelReply call(ModelCall request); }
// execution/Limits.java
public record Limits(int modelTurns, int toolCalls, int modelAttempts,
    long runMillis, long modelMillis, long commandMillis, long evaluationMillis) {}
// execution/RunSpec.java
public record RunSpec(String runId, String benchmarkVersion, String profileRevision,
    SnapshotRef initialSnapshot, String initialConversationBlobId,
    long evaluatorSeed, Limits limits) {}
// execution/RunView.java
public record RunView(String runId, String status, long version,
    SnapshotRef snapshot, long deadlineEpochMillis, int modelTurns,
    int modelAttempts, int toolCalls, String resultBlobId) {}
// execution/CancelCommand.java
public record CancelCommand(String commandId, long expectedVersion) {}
// execution/CommandResult.java
public record CommandResult(String commandId, String status, long stateVersion) {}
// runs/RunEvent.java
public record RunEvent(String runId, long sequence, String eventId,
    String type, String payloadBlobId) {}
```

Use Temporal annotations on `CodingWorkflow` in Task 7:

```java
@WorkflowInterface
public interface CodingWorkflow {
    @WorkflowMethod RunView run(RunSpec spec);
    @QueryMethod RunView view();
    @UpdateMethod CommandResult cancel(CancelCommand command);
}
```

`RunActivities` in Task 7 exposes `ModelReply model(ModelCall)`, `String appendConversation(String previousBlobId, String entryJson)`, `ToolOutcome tool(ToolInvocation)`, `void publish(RunEvent)`, and `void stopRunContainers(String runId)`. Define `ToolInvocation(runId, invocationId, snapshot, ToolRequest request, evaluatorSeed, deadlineEpochMillis)` and `ToolOutcome(snapshot, resultJson, Evaluation evaluation)` in `execution/`; evaluation is null for non-submit tools. JSON entries use versioned DTOs and strict validation, not raw SDK serialization. One Activity handles one model attempt or one tool invocation, never the full coding loop.

## Task 1: Establish the verified application and durable service baseline

**Files:** Create `build.gradle`, `settings.gradle`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`, `gradle.lockfile`, `.gitignore`, `compose.yaml`, `config/images.lock`, `J/HarnessApplication.java`, `J/config/TemporalConfiguration.java`, `src/main/resources/application.yaml`, `src/main/resources/db/migration/V1__create_model_profiles.sql`, `T/config/InfrastructureIT.java`, `docs/development.md`.

**Interfaces:** Produce role profiles `api`, `worker`, `runner`; a JDBC data source; explicitly configured `WorkflowServiceStubs`, `WorkflowClient` and `WorkerFactory` beans. Keep runner startup separate from worker startup. Use three PostgreSQL databases/roles for application, Temporal default persistence and Temporal visibility. Only Flyway owns application tables; Temporal's schema tooling owns its databases.

- [ ] Add checksum-pinned Gradle wrapper 9.8.0, the selected Boot plugin and native dependency BOM, Java release 21, Spring AI BOM 2.0.1, Temporal SDK/testing 1.36.1, JDBC/Flyway/PostgreSQL, validation, actuator, JUnit and integration-test support. Use `test` for unit tests and `integrationTest` for `*IT`, with both included in `check` and required checks on test discovery. Commit dependency locks. Use direct SDK beans rather than a Temporal Boot starter.
- [ ] Write `InfrastructureIT` before application/service configuration. It must connect to real services, query persisted data after a reconnect, and round-trip a small probe DTO through Temporal's chosen data converter. Declare `record Probe(String value) {}` in the test; this avoids depending on Task 2. Example assertions:

```java
@Test void databaseAndPayloadRoundTrip() {
    assertEquals(1, jdbc.queryForObject("select 1", Integer.class));
    Probe ref = new Probe("durable-probe");
    var converter = io.temporal.common.converter.DefaultDataConverter.getDefaultInstance();
    var payloads = converter.toPayloads(ref).orElseThrow();
    assertEquals(ref, converter.fromPayloads(0, java.util.Optional.of(payloads),
        Probe.class, Probe.class));
    assertNotNull(temporalService.blockingStub().getSystemInfo(
        io.temporal.api.workflowservice.v1.GetSystemInfoRequest.getDefaultInstance()));
}
```

Here `jdbc` is an injected `JdbcTemplate`; `temporalService` is the configured `WorkflowServiceStubs`. Supply these test fields with a Spring test configuration, not mocks. Use an explicit data converter with a verified Jackson dependency set if Boot/Temporal defaults conflict; assert the selected converter against the same payload schema in the worker and client.

- [ ] Run `./gradlew integrationTest --tests '*InfrastructureIT'`; observe failure attributable to the missing application/service wiring, not a silently skipped test.
- [ ] Configure durable named PostgreSQL volumes, Temporal server 1.32.0 and its matching schema setup, loopback port bindings, health checks and startup dependencies. Resolve image digests into the lock and Compose configuration. `docker compose down` must retain volumes; no `-v` in operating instructions.
- [ ] Add migration `V1__create_model_profiles.sql` with immutable `model_profile_revision(id text primary key, profile_json jsonb not null, sha256 text not null unique, created_at timestamptz not null)`. Store credential references only. Add role startup tests preventing a runner-only process from starting a Workflow poller.
- [ ] Run `docker compose up -d --wait postgres temporal` followed by `docker compose run --rm --no-deps temporal-namespace` and `./gradlew integrationTest --tests '*InfrastructureIT'`. Record effective dependency versions and image digests in `docs/development.md`, including model inference not yet tested. Verify Flyway's description is `create model profiles`.
- [ ] Commit `build: establish Spring Boot and durable service baseline` with only these files and the version evidence.

## Task 2: Persist immutable workspaces and idempotent file edits

**Files:** Create `J/workspace/{SnapshotRef,WriteRequest,WorkspaceStore,JdbcWorkspaceStore,WorkspacePathPolicy,SnapshotHasher}.java`, `src/main/resources/db/migration/V2__create_workspace_snapshots.sql`, `benchmarks/task-tracker-v1/starter/{build.gradle,settings.gradle,gradlew,gradlew.bat,gradle/wrapper/gradle-wrapper.jar,gradle/wrapper/gradle-wrapper.properties,gradle.lockfile,REQUIREMENTS.md,src/main/java/example/tasktracker/TaskTracker.java}`, `T/workspace/{WorkspacePathPolicyTest,WorkspaceStoreIT}.java`.

**Interfaces:** Implement the workspace contracts above. A source snapshot has a sorted path→UTF-8-content mapping. Hash a version marker plus length-prefixed UTF-8 path/content bytes in lexical path order, not ambiguous concatenated text. Validate run ownership on reads even though content is deduplicated globally.

- [ ] Add the locked target Gradle plain-Java build using a main class `example.tasktracker.TaskTracker`, JAR output `build/libs/task-tracker.jar`, Java release 21, and a pinned JUnit test dependency. Copy the full behavior table from the spec into REQUIREMENTS.md. The starter main throws `UnsupportedOperationException("Implement the requirements")`; no reference implementation is present.
- [ ] Write database tests using a seeded starter and the actual PostgreSQL schema. Declare `store` as the injected `WorkspaceStore` and use fresh run IDs per test:

```java
@Test void repeatedWriteReturnsOneSnapshot() {
    var parent = store.seed("run-1", "task-tracker-v1");
    var write = new WriteRequest("run-1", "write-1", parent,
        "README.md", "absent", "Task tracker usage\n");
    var first = store.write(write);
    assertEquals(first, store.write(write));
    assertFalse(store.files("run-1", parent).containsKey("README.md"));
    assertEquals("Task tracker usage\n", store.files("run-1", first).get("README.md"));
    assertThrows(IllegalArgumentException.class, () -> store.write(
        new WriteRequest("run-1", "write-1", parent, "README.md", "absent", "changed")));
}
```

Add parameterized cases for `../`, absolute paths, backslashes, forbidden build edits, oversized files, quota overflow, wrong expected hashes, and cross-run reads. Ensure future tar staging rejects symlinks rather than just lexical paths.

- [ ] Run `./gradlew test --tests '*WorkspacePathPolicyTest' integrationTest --tests '*WorkspaceStoreIT'`; confirm the missing implementation fails.
- [ ] Create tables `workspace_snapshot(sha256 PK, files_json, byte_count, file_count)`, `run_snapshot(run_id, sha256, PRIMARY KEY(run_id,sha256))`, and `workspace_write_receipt(run_id, invocation_id, input_sha256, result_sha256, PRIMARY KEY(run_id,invocation_id))`. All content/digest validation and receipt insertion happen in one transaction. A unique-key race reads the existing receipt and compares the input hash; never overwrite it.
- [ ] Implement path rules and canonical hashing, immutable seed import and write transactions. The expected file hash is checked against the supplied parent snapshot; there is no mutable workspace head in this store. Write receipts cannot change which snapshot the Workflow has adopted.
- [ ] Run the targeted tests with a real database, including a connection-loss rollback test between snapshot insertion and receipt insertion. Confirm old snapshots and duplicate responses remain stable.
- [ ] Commit `feat: persist immutable coding workspaces`.

## Task 3: Build a reconciled, isolated command runner

**Files:** Create `J/runner/{Operation,InvocationRequest,InvocationResult,Runner,JdbcRunner,DockerCommandClient,RunnerSupervisor,InvocationHasher}.java`, `src/main/resources/db/migration/V3__create_runner_invocations.sql`, `runner/{Dockerfile,entrypoint.sh,commands.json}`, `T/runner/{InvocationHasherTest,RunnerIT}.java`, `scripts/runner_fault_test.py`.

**Interfaces:** `Runner` methods are durable and nonblocking; the runner process polls its database ledger every second. `DockerCommandClient` uses `ProcessBuilder(List<String>)` with fixed argument vectors, never `/bin/sh -c` with model content. Candidate source is data staged as a checked tar stream through Docker, not command text.

- [ ] Write a real-container test that submits the same `(runId, invocationId, snapshot, operation)` twice and counts one labeled container. Change the snapshot under the same ID and assert rejection. Test controls pause at `AFTER_LEDGER_COMMIT`, `AFTER_CREATE`, `AFTER_START`, `AFTER_EXIT`, `AFTER_RESULT_COMMIT`; fault controls are available only in the recovery-test profile.

```java
@Test void restartingSupervisorFindsExistingCommand() {
    var request = new InvocationRequest("run-3", "build-1", snapshot,
        Operation.BUILD, System.currentTimeMillis() + 120_000);
    runner.ensureStarted(request);
    awaitContainerRunning("build-1");
    restartRunnerProcess();
    runner.ensureStarted(request);
    assertEquals(1, ownedContainerCount("build-1"));
    var result = awaitResult("build-1");
    assertEquals("build-1", result.invocationId());
    assertFalse(result.uncertain());
}
```

Implement the four test helpers in `RunnerIT`: Docker label inspection, process restart using the built application in `runner` profile, bounded polling (maximum 120 seconds), and ledger result lookup. They operate actual processes, not mocked runner instances. The `snapshot` field is a Task 2 seeded/edited fixture.

- [ ] Run `./gradlew integrationTest --tests '*RunnerIT'`; observe expected missing-runner failures.
- [ ] Create `runner_invocation(run_id, invocation_id PK, input_sha256, snapshot_sha256, operation, deadline_at, status, requested_cancel, attempt, container_name, result_json)` and `artifact_blob(sha256 PK, media_type, content bytea, size_bytes)`. Store bounded logs/JAR bytes by digest, retaining provenance. Cap JAR artifacts at 16 MiB and reject oversized output. The phase-one fixture is deliberately small.
- [ ] Implement a supervisor lease per invocation with compare-and-set status updates. Unique container name plus matching input-hash labels makes creation recoverable if a process dies before recording the Docker ID. Labels include a project ownership marker; never inspect, stop or remove unrelated user containers. Detect a name collision with mismatched labels and fail visibly.
- [ ] Bake the trusted Gradle 9.8.0 distribution, Java 21 toolchain, plugins, and dependency caches into the pinned image. Fixed BUILD is `gradle --offline --no-daemon --console=plain jar`; TEST is `gradle --offline --no-daemon --console=plain test`. The plain-Java target uses no Boot plugin. All arguments, settings/build files, wrappers, lockfiles, init scripts, and `GRADLE_USER_HOME` are runner-owned. Use separate build/test copies and bounded writable user-home, project-cache, and output directories, with no host cache mounts or runtime downloads. Validate the image by running actual offline `jar` and `test` commands on fixtures under the real non-root, network-disabled, read-only-root resource limits. Include any single-use Gradle JVM in deadline/process/memory accounting. Collect `build/libs/task-tracker.jar` and `build/test-results/test/`; preserve candidate test execution, but do not trust its XML for benchmark verdicts.
- [ ] Reconcile CREATED/RUNNING/EXITED containers after restart, enforce absolute deadlines and requested cancellation, retain exit evidence before cleanup. If a stopped container/artifact disappeared before receipt persistence, store UNCERTAIN, increment attempt at most once, and rerun only in a new isolated copy. Task-level compilation/test failure is a result, not an infrastructure retry.
- [ ] Run isolation probes for outbound network, host file/socket access, protected build changes, process/memory limits, stdout flooding, timeout and cancellation. Assert logs carry truncation metadata and no uncontrolled path escapes.
- [ ] Run `python3 scripts/runner_fault_test.py --all-boundaries` and the targeted integration tests. Record one container per attempt, correct receipt reuse, and uncertainty when evidence is deliberately removed.
- [ ] Commit `feat: supervise isolated build and test invocations`.

## Task 4: Prove the independent task-tracker evaluator

**Files:** Create `J/benchmark/{Evaluation,Evaluator,TaskTrackerEvaluator,TaskTrackerDefinition}.java`, `T/benchmark/{TaskTrackerEvaluatorIT,fixtures/GoodTracker.java,fixtures/BrokenTracker.java}`, `benchmarks/task-tracker-v1/evaluator-cases.json`, `runner/evaluate-commands.json`.

**Interfaces:** `TaskTrackerEvaluator` implements `Evaluator`, uses the fixed runner build/test environment, and independently drives candidate processes. `TaskTrackerDefinition` returns benchmark version, starter digest, model-visible requirements, tool schemas and evaluator version. The evaluator's controller/report storage are never mounted in candidate containers.

- [ ] Define exact case names: `empty`, `add_list`, `complete`, `complete_twice`, `restart`, `isolated_dirs`, `unicode_spaces`, `duplicate_descriptions`, `invalid_command`, `invalid_args`, `invalid_id`, `invalid_description`, `failed_write`, `agent_tests`, `readme`. Use Java `Random(seed)` for recorded test descriptions and task counts, not model-generated expected answers.
- [ ] Write evaluator tests with private fixture snapshots. `GoodTracker.java` is test-only and implements the public contract; `BrokenTracker.java` fixtures deliberately omit persistence, hardcode example output, mutate on unknown IDs, hang, or print a fake success report. They are not starter files or prompts.

```java
@Test void evaluatorDoesNotTrustCandidateSuccessText() {
    SnapshotRef candidate = privateFixture("fake-success");
    Evaluation result = evaluator.evaluate("eval-1", "submit-1", candidate,
        42017L, System.currentTimeMillis() + 180_000);
    assertFalse(result.passed());
    assertFalse(result.failedCases().isEmpty());
}
```

`privateFixture(String variant)` is a test helper in `TaskTrackerEvaluatorIT`: it imports the starter, writes the selected private source through `WorkspaceStore`, and supplies a README and discovered test; it never exposes fixture directories to the agent. `evaluator` is the real controller. Test each negative variant separately, including an assertion that the starter fails.

- [ ] Run `./gradlew integrationTest --tests '*TaskTrackerEvaluatorIT'`; confirm the evaluator does not yet exist/pass.
- [ ] Implement fresh candidate runtime containers with read-only JAR and writable data only. Each `java -jar ...` invocation has a 5-second process limit nested under the overall evaluation deadline. Reuse a candidate data volume within a case requiring persistence; discard it between cases and submissions. Compare exact stdout, stderr rules and process exit; check that invalid operations preserve subsequent listing.
- [ ] Build the candidate outside the evaluator controller, run its discovered tests, then run independent cases. A candidate process cannot write the evaluator verdict. Record README/test presence separately; zero agent tests or missing README fail their explicit requirements, while stylistic quality is not automatically scored.
- [ ] Persist `Evaluation` against the submitted snapshot and JAR digest. Repeated submit ID/input returns the existing evaluation. If a build fails, produce a failed evaluation with build diagnostics; do not throw an unclassified success-path exception.
- [ ] Run all positive and mutant cases plus a test that an attempted evaluator path read cannot see evaluator files. Commit `feat: independently evaluate task-tracker submissions`.

## Task 5: Implement one-call local model adapters and frozen profiles

**Files:** Create `J/model/{ModelCall,ToolRequest,ModelReply,ModelAdapter,ScriptedModelAdapter,OllamaModelAdapter,LocalProfileProbe,ProfileRevision}.java`, `J/runs/{BlobStore,JdbcBlobStore}.java`, `J/config/ModelConfiguration.java`, `config/local-profile.example.yaml`, `T/model/{OllamaAdapterTest,ProfileProbeTest}.java`, `T/model/fixtures/` HTTP request/response fixtures.

**Interfaces:** ProfileRevision contains adapter name, loopback endpoint, installed model tag/full digest, explicit generation/context settings, tested capabilities and credential reference if required. A durable blob repository stores conversations; add `J/runs/BlobStore.java` with `String put(String mediaType, byte[] bytes)` and `byte[] get(String digest)`, backed by `artifact_blob`. Model Activities resolve blobs and secret references outside Workflow code.

- [ ] Use JDK `HttpServer` for fixture-server tests on an ephemeral loopback port. Implement a request counter and fixture response queue inside `OllamaAdapterTest`. Validate full request construction and one HTTP call per ModelAdapter call. A native tool response returns a `ToolRequest`; it must not invoke Java file tools or make a second HTTP request automatically.

```java
@Test void nativeToolRequestIsReturnedWithoutExecution() {
    serverReply("native-write-file-response.json");
    ModelReply reply = adapter.call(requestWithConversation("model-1"));
    assertEquals("write_file", reply.tools().getFirst().name());
    assertEquals(1, httpRequestCount());
    assertEquals(0, actualToolExecutions.get());
}
```

Declare the fixture helpers in this test class: `serverReply` queues resource bytes, `requestWithConversation` saves a known conversation via BlobStore, `httpRequestCount` returns the server counter, and `actualToolExecutions` is an `AtomicInteger` attached to a sentinel callback which throws if invoked. Test absent usage as null, not zero; malformed JSON, multiple tool results/correlation, oversized/context-limited inputs, transport errors, and elapsed request timeout.

- [ ] Run `./gradlew test --tests '*OllamaAdapterTest' --tests '*ProfileProbeTest'`; confirm red behavior before adapter code.
- [ ] Call Spring AI 2.0.1 `ChatModel` directly with tool definitions; do not use an auto-looping `ChatClient`. Use `OllamaChatOptions` for the selected API. Disable SDK retries and model auto-pull. Map wire messages using explicit DTOs, preserving tool-call IDs and deterministic generated IDs when Ollama omits one. IDs are unique within the turn and echoed with tool results.
- [ ] Probe `/api/tags` and `/api/show` through the local service, record the full installed digest and tool capability, and require a live read-only tool round trip before marking a profile eligible. Model tags ending in `:cloud` and provider metadata indicating remote execution are rejected. If local residency cannot be established, fail setup instead of guessing. Recheck digest before calls; tag drift fails with `PROFILE_DRIFT` rather than switching weights mid-run.
- [ ] Set the example endpoint to `http://127.0.0.1:11434` and require `HARNESS_OLLAMA_MODEL` with no silent default. Keep real machine model names out of a committed selected profile until the operator chooses/probes one. The adapter supports only this local protocol initially.
- [ ] Implement ScriptedModelAdapter from a sequence of ModelReply fixtures with counters and crash-test barriers. It implements the identical contract and never accesses the live endpoint.
- [ ] Run unit/contract tests without Ollama inference, then commit `feat: add explicit local model invocation boundary`.

## Task 6: Deliver accepted commands without duplicate runs

**Files:** Create `J/execution/{Limits,RunSpec,RunView,CancelCommand,CommandResult}.java` and `J/runs/{StartCommand,StartResult,RunCommandService,CommandDispatcher,RunEvent,RunProjectionRepository}.java`, `src/main/resources/db/migration/V4__create_run_commands_and_events.sql`, `T/runs/{RunCommandServiceIT,CommandDeliveryIT}.java`.

**Interfaces:** Define `StartCommand(String commandId, String benchmarkVersion, String profileRevision, Limits limits)`; `StartResult(String runId, String commandId, String deliveryStatus)`; `RunCommandService.start(StartCommand)` and `RunCommandService.cancel(String runId, CancelCommand)` accept commands durably. The dispatcher has `dispatchOnce()` and talks through `WorkflowGateway.start(RunSpec)`, `WorkflowGateway.cancel(String runId, CancelCommand)`, `WorkflowGateway.describe(String runId)`; create `J/runs/WorkflowGateway.java` here and the real Temporal implementation in Task 7. Gateway start outcomes are `STARTED`, `ALREADY_EXISTS`, or transient error; an existing workflow's input identity must match.

- [ ] Write a database test that concurrent duplicate start commands return one run ID, conflicting payload reuse is rejected, and a crash after gateway start but before acknowledgment causes a deduplicated redelivery. Use a durable fake gateway for the focused transaction test; Task 9 repeats it against Temporal.

```java
@Test void lostStartAcknowledgmentDoesNotCreateAnotherRun() {
    StartCommand command = new StartCommand("cmd-1", "task-tracker-v1",
        validProfileId, defaultLimits);
    StartResult accepted = commands.start(command);
    gateway.failOnceAfterCreatingWorkflow();
    assertThrows(RuntimeException.class, dispatcher::dispatchOnce);
    dispatcher.dispatchOnce();
    assertEquals(accepted.runId(), commands.start(command).runId());
    assertEquals(1, gateway.createdWorkflowCount());
}
```

Declare `gateway` as `RecordingWorkflowGateway`, a test implementation holding a map keyed by run ID and the named fault hook; `commands`/`dispatcher` use actual database repositories. `validProfileId` and `defaultLimits` are persisted fixture values matching the spec.

- [ ] Run `./gradlew integrationTest --tests '*RunCommandServiceIT' --tests '*CommandDeliveryIT'` before service implementation.
- [ ] Create `run_request(run_id PK, command_id UNIQUE, input_sha256, spec_json, created_at)`, `run_command(command_id PK, run_id, kind, payload_sha256, payload_json, accepted_at, delivered_at, outcome_json)`, `run_event(run_id, sequence, event_id UNIQUE, type, payload_blob_id, PRIMARY KEY(run_id,sequence))`, and `run_projection(run_id PK, version, status, snapshot_sha256, updated_at, terminal_result_blob_id)`.
- [ ] Generate stable run identity from the accepted start command, seed the workspace and freeze profile/definition/input in the same recoverable acceptance flow. Commit run request plus START outbox atomically. Retain deduplication tombstones past normal payload cleanup; repeating a completed command cannot create a new workflow even after Temporal history retention.
- [ ] Dispatch with bounded backoff and recoverable claim leases. Configure Temporal workflow ID conflict/reuse policy to avoid reuse of the same run identity. No transaction spans PostgreSQL and Temporal. CANCEL rows wait for their START delivery if necessary; an accepted queued cancellation shown in the API is not proof that the Workflow has applied it.
- [ ] Apply RunEvent idempotently: same ID/payload is a no-op; same ID with different payload is an integrity error. Ordered projection updates require contiguous sequences; expose gaps as stale. Query/reconcile Temporal on reconnect or detected lag; never synthesize completed state from log text.
- [ ] Run tests with injected database rollback at acceptance, start dispatch, acknowledgment, event insert and projection update. Commit `feat: persist recoverable run commands and projections`.

## Task 7: Execute the bounded durable coding loop

**Files:** Create `J/execution/{CodingWorkflow,CodingWorkflowImpl,RunActivities,RunActivitiesImpl,ToolInvocation,ToolOutcome,ToolRouter}.java`, `J/runs/TemporalWorkflowGateway.java`, `T/execution/{CodingWorkflowTest,ToolRouterTest}.java`, `T/execution/fixtures/scripted-task-tracker.json`, `src/main/resources/db/migration/V5__create_model_attempts_and_run_admission.sql`.

**Interfaces:** Use the shared contracts above; keep the Task 6 execution DTOs. Workflow-local state contains immutable spec, latest snapshot reference, conversation blob reference, sequence/version, logical counts, attempt counts, absolute deadline and terminal result reference. `ToolRouter` dispatches only the six documented tools. Tool ledger identity derives from run ID + logical turn + tool index; provider call IDs are correlation only.

- [ ] Write a Temporal test-environment scenario: scripted read → write broken code → test failure → repair → submit failure → revise → submit pass. The test's evaluator is a controlled Activity fake; Task 9 uses the real evaluator. Assert snapshots advance only from recorded successful edits, nonpassing submit keeps the loop active, and passing submit is the only success path.

```java
@Test void failedSubmissionCanBeRepairedWithinTheBudget() {
    activityFixtures.enqueueSubmission(false);
    activityFixtures.enqueueSubmission(true);
    RunView completed = workflow.run(spec);
    assertEquals("SUCCEEDED", completed.status());
    assertEquals(2, activityFixtures.submissionCount());
    assertEquals(activityFixtures.lastSubmittedSnapshot(), completed.snapshot());
}
```

`workflow` is a stub from `TestWorkflowEnvironment`; `activityFixtures` implements RunActivities and the scripted sequence listed above; `spec` uses stored fixture references and the stated Limits. Define them in `CodingWorkflowTest` setup. Add separate tests for text-only completion, invalid tool schema, quota exhaustion, context overflow, cancel-before-success, already-terminal cancel, expired deadline, late Activity result, and exhausted infrastructure attempts.

- [ ] Run `./gradlew test --tests '*CodingWorkflowTest' --tests '*ToolRouterTest'`; inspect the expected failures.
- [ ] Implement deterministic orchestration: initialize start time with `Workflow.currentTimeMillis`; run a durable timer concurrently with the work scope; on expiry cancel the work scope and stop owned runner processes in a detached cleanup scope. Derive IDs from stable counters, not wall-clock/random host APIs. Keep I/O in Activities.
- [ ] Add `model_attempt(run_id, invocation_id, attempt, input_sha256, status, owner_token, request_blob_id, response_blob_id, started_at, ended_at, PRIMARY KEY(run_id,invocation_id,attempt))` and `run_admission(slot_id integer PRIMARY KEY CHECK(slot_id=1), run_id UNIQUE, acquired_at)`. Persist attempt ownership, response receipts and UNKNOWN outcomes transactionally. An Activity redelivery checks the receipt/attempt state before contacting the provider; after an unresolved timed-out attempt, only the Workflow can allocate the next attempt number. Admission reservations are released only on authoritative terminal confirmation or a proved never-started request, not on worker process death.
- [ ] Configure Temporal Activity retries `maximumAttempts=1` for model invocations and implement the allowed two attempts explicitly in Workflow state so attempt budgets are visible. Persist the ModelCall request/attempt marker before dispatch and an immutable response receipt before Activity return. If replay redelivers an Activity with a saved response, return it; an uncertain unreceipted request records UNKNOWN and consumes the next allowed attempt. Do not allow concurrent stale/new attempts to overwrite one receipt; use compare-and-set attempt ownership. Timeouts can still leave provider-side work running; record that limitation.
- [ ] Use explicit tool Activity timeouts and small safe retry policies for idempotent receipts; runner invocations deduplicate independently of Activity retries. For each tool call in a model response, inject the current snapshot and process sequentially, using the updated snapshot for subsequent calls. Preserve the original assistant tool-call batch and one correlated result per call in the conversation.
- [ ] Apply cancellation through a Workflow Update with command deduplication and expected-version checks in the Workflow, not a stale projection. Increment the version at accepted state changes. Applied cancellation wins over subsequent success; already-terminal runs return a terminal rejection. Prevent further dispatch, stop outstanding runner work, then publish final cancellation. If cleanup cannot be confirmed, show a nonterminal stopping state and operational error until reconciliation succeeds.
- [ ] Write immutable semantic events through publish Activities with stable IDs and monotonically increasing sequences. Publish and receipt retries cannot double-count usage. Model result payloads and source stay in BlobStore; Workflow history contains bounded summaries/refs. Enforce maximum conversation bytes and profile context before another call; no silent truncation.
- [ ] Configure one active benchmark slot across worker versions through a durable database admission reservation held by a run, not a process. Waiting runs remain queued; release idempotently on terminal state and reconcile orphan reservations against Temporal. The execution deadline begins on slot acquisition. Cancellation works while waiting. Record queue time separately.
- [ ] Run targeted tests and a real-service scripted smoke run. Commit `feat: orchestrate recoverable coding turns and tool execution`.

## Task 8: Expose the local operator API and command-line client

**Files:** Create `J/api/{RunController,ProfileController,ApiSecurityConfiguration}.java`, `J/runs/RunQueryService.java`, `scripts/harness.py`, `T/api/{RunApiIT,ApiSecurityTest}.java`, `docs/api.md`.

**Interfaces:** Implement `POST /api/runs`, `GET /api/runs`, `GET /api/runs/{id}`, `GET /api/runs/{id}/events?after=N`, `POST /api/runs/{id}/cancel`, `GET /api/profiles`, `POST /api/profiles/probe`, and `GET /api/runs/{id}/artifacts/{digest}`. POST start accepts `{commandId, benchmarkVersion, profileRevision, limits}`; POST cancel accepts `{commandId, expectedVersion}`. Artifacts are authorized by run association and typed digest, never arbitrary filesystem paths.

- [ ] Write an API integration test posting the same command twice, listing ordered events across reconnect, and cancelling with both stale and current versions. Assert accepted-vs-applied distinctions and explicit projection staleness. Use MockMvc/HTTP integration against a real repository with the scripted Workflow.

```python
# Contract example for scripts/harness.py's request helper and its integration test.
import json, urllib.request
payload = json.dumps({"commandId": "api-test-1", "benchmarkVersion": "task-tracker-v1",
                      "profileRevision": "scripted-v1", "limits": limits}).encode()
req = urllib.request.Request(base + "/api/runs", data=payload,
                             headers={"Content-Type": "application/json"}, method="POST")
with urllib.request.urlopen(req) as response:
    created = json.load(response)
assert created["deliveryStatus"] == "ACCEPTED"
assert created["runId"]
```

Here `limits` is the full Limits JSON from the spec, `base` is the test server's loopback URL, and `scripted-v1` is a test-only seeded profile. Reject unknown fields and invalid limits before command acceptance.

- [ ] Run `./gradlew test --tests '*ApiSecurityTest' integrationTest --tests '*RunApiIT'` and observe failures before controller implementation.
- [ ] Return 202 for durably accepted asynchronous commands, 409 for conflicting command identity or authoritative version conflict, 400 for malformed/unsupported input, 404 for unknown run/artifact. Add delivery/application outcomes to command queries through run detail. Completed command retries return their prior result.
- [ ] Bind `127.0.0.1`. Require JSON for mutation requests, reject foreign Origin/Fetch-Site headers, and do not enable CORS. Same-origin browser requests require CSRF handling when introduced; initial Python CLI sends no cookies/Origin and uses the explicit loopback API. Add tests that cross-origin browser requests and form/plaintext mutations are rejected. Do not advertise hosted multi-user security.
- [ ] Implement Python standard-library CLI subcommands `profiles`, `probe`, `start`, `status`, `events`, `cancel`, `download`. `start` prints the run ID and command ID; reuse a supplied `--command-id` for retries. `events --follow` polls with last acknowledged sequence and reconnects without duplicate output. Logs/artifacts are output data, never executed. No shell interpolation.
- [ ] Document a complete scripted run and artifact retrieval. Run API integration and CLI reconnect tests; commit `feat: operate local coding runs through API and CLI`.

## Task 9: Prove crashes, deadlines and deployment continuity

**Files:** Create `J/config/WorkerDeploymentConfiguration.java`, `T/recovery/{RecoveryIT,DeploymentIT,SecretRedactionIT,TelemetryOutageIT}.java`, `scripts/recovery_test.py`, `scripts/deployment_test.py`, `config/recovery-test.yaml`, `docs/operations.md`.

**Interfaces:** Worker deployment name is `coding-harness`, Workflow type is PINNED, each built worker has an immutable image/build ID. Use the current Worker Deployment API, not deprecated compatibility sets or assignment rules. v1 and v2 workers may coexist, sharing service persistence and the runner ledger. API, runner, and workflow worker processes have separate lifecycle controls.

- [ ] Write process-level tests before adding deployment configuration. Create barriers based on persisted semantic events, not fixed sleeps. The Python scripts use standard-library subprocess/HTTP calls and Docker argument lists; spawn only labeled task test processes. An assertion example:

```python
before = get_run(run_id)
kill_worker("v1")
start_worker("v1")
after = wait_for_terminal(run_id, timeout_seconds=180)
assert after["runId"] == before["runId"]
assert after["status"] == "SUCCEEDED"
assert completed_model_invocation_ids(run_id) == expected_model_invocation_ids
assert container_count_for_invocation(build_invocation_id) == 1
```

Implement the named helpers in `scripts/recovery_test.py`: `get_run` uses the API; kill/start use subprocess-owned worker PIDs/build arguments; wait polls with a deadline; invocation assertions read durable events and Docker labels. Define expected IDs from the scripted fixture, not from whatever the system happened to emit. Preserve stopped containers until the assertion finishes.

- [ ] Run `./gradlew integrationTest --tests '*RecoveryIT' --tests '*DeploymentIT'`; confirm tests fail without the required configuration/behavior. Do not emulate a crash by throwing an exception in the same JVM.
- [ ] Test kills after a source receipt, during test execution, after runner exit before receipt acknowledgment, after command delivery before acknowledgment, and during event projection. Restore processes and assert the same run and snapshot lineage, idempotent receipts, bounded attempts, and no duplicate evaluator success.
- [ ] Restart Temporal itself with its persistent databases/volumes retained. Repeat with a deadline expiring during downtime; the expired run stops and never receives a fresh budget. Restart the runner at each Task 3 boundary, and test cancellation with worker temporarily absent. Verify no further call starts after applied cancellation.
- [ ] Configure pinned worker versions and use the tested CLI/SDK deployment API to make v1 current, start run A, make v2 current, start run B, stop/restart a v1 worker, and complete both on their assigned versions. Roll current back to v1 for new run C while retaining v2 for B if still running. Assert a changed v2 workflow cannot accidentally take A. If all required v1 workers are removed, show waiting/unavailable, then resume when v1 returns; do not claim automatic migration to arbitrary new code.
- [ ] Add structured run/invocation IDs to logs and Micrometer spans/counters, with bounded metric labels. Test a disconnected exporter while a run succeeds and history remains queryable. Inject sentinel credentials and scan captured logs, API responses, Workflow payloads and candidate environment; never include prompt/source/tool content in diagnostic export by default.
- [ ] Document recovery and worker-draining procedures with exact tested commands and pinned CLI version, then run `python3 scripts/recovery_test.py --all` and `python3 scripts/deployment_test.py --forward-and-rollback`. Record test artifacts and limitations.
- [ ] Commit `test: prove coding-run recovery and deployment continuity`.

## Task 10: Run the real local benchmark and package completion evidence

**Files:** Create `scripts/local_acceptance.py`, `docs/evidence/local-task-tracker.md`, `T/acceptance/LocalAcceptanceIT.java`; update `README.md`, `docs/development.md`, `docs/sprints/01-observable-loop.md`, and the selected local profile revision through the application configuration flow.

**Interfaces:** `local_acceptance.py --model NAME --seed N --scenario baseline|worker-crash|deployment` calls the profile probe and operator API. It stores a JSON evidence bundle keyed by run ID. It must not implement the target program or patch the model's workspace to make the benchmark pass.

- [ ] Add an explicit `liveLocalTest` Gradle test task, excluded from normal `check`/`build`, and a test requiring an explicitly selected installed model. Normal CI runs no paid/remote inference and does not silently count skipped live acceptance as a pass. Missing local prerequisites produce a setup failure in an explicitly requested live run.
- [ ] Choose the model from the installed inventory with the operator, run the read-only tool probe, freeze full model digest/context/settings, and confirm local residency. Record local Ollama version. No model download is required by this plan and no remote provider is configured.
- [ ] Write the live acceptance assertion around the trusted evaluation, not text:

```python
result = run_benchmark(model=args.model, seed=args.seed, scenario=args.scenario)
assert result["profile"]["placement"] == "local"
assert result["evaluation"]["snapshotSha256"] == result["finalSnapshotSha256"]
assert result["evaluation"]["passed"] is True
assert result["artifactSha256"] == result["evaluation"]["artifactSha256"]
assert result["recoveryChecksPassed"] is True
```

Implement `run_benchmark` in `scripts/local_acceptance.py` using the CLI/API contracts from Task 8, the process controls from Task 9, and trusted artifact hashing. For baseline, `recoveryChecksPassed` refers to the separately recorded recovery-suite evidence, not an unperformed crash. Include per-scenario evidence fields so that distinction is visible.

- [ ] Run the baseline from a fresh starter, then a worker-crash run and deployment run. Each is a new benchmark run with its own seed and immutable configuration; the crash/deployment within that run must retain its original run ID. Do not demand identical generated source across nondeterministic runs.
- [ ] Record model/tool counts, all physical attempts including unknown outcomes, elapsed/queue time, usage availability, source/JAR digests, private evaluator version/seed, failures/repairs, deployment IDs, and terminal reason. If any live run fails, report it as a failed benchmark and investigate the recorded cause; do not weaken assertions or repair candidate code by hand. A model/limit change creates a new profile/run for comparison.
- [ ] Retrieve the successful candidate JAR and source snapshot through the artifact API and rerun evaluator cases in a fresh environment. Manually read its README/tests as supplementary quality review; distinguish that review from the executable verdict.
- [ ] Run `./gradlew build`, the recovery/deployment scripts, and the explicitly configured `./gradlew liveLocalTest` suite appropriate to the final changes. Update documentation with actual evidence. Do not repeat inference without a changed hypothesis or validation need.
- [ ] Commit `docs: record local coding benchmark evidence`, push the implementation feature branch and open a PR. Do not merge directly. Mark only the local milestone criteria evidenced below as complete.

## Acceptance coverage and milestone boundaries

| Requirement | Implementation / proof |
|---|---|
| C1 independent evaluator | Task 4 positive/negative fixtures and trusted verdicts |
| C2 edit-test-repair-submit | Tasks 2–4, 7; live evidence Task 10 |
| C3 actual local completion | Tasks 5, 10; cannot be satisfied by scripted adapter |
| C4 workspace/runner scope | Tasks 2–4 isolation, quotas and immutable build tests |
| C5 source recovery | Tasks 2, 7, 9 |
| C6 in-flight build recovery | Tasks 3, 9 |
| C7 uncertain runner outcome | Tasks 3, 9 |
| C8 deployment continuation | Task 9 actual v1/v2 processes and rollback |
| C9 service restart/deadlines | Tasks 1, 7, 9 |
| C10 command/projection recovery | Tasks 6, 8, 9 |
| C11 cancellation and budgets | Tasks 3, 7, 9 |
| C12 secrets/telemetry outage | Tasks 5, 8, 9 |
| Original P1-02 through P1-14 | Relevant local-only behaviors retained across Tasks 1–10; P1-10 now evaluates submitted code, P1-11 recovers execution |
| P1-16 through P1-19 | Task 9 plus runner reconciliation; same-run recovery and deployment evidence |
| Original remote P1-01 | Deferred to a subsequent remote-adapter plan; no parity claim |
| Original browser P1-15 | Deferred to a console plan; CLI/API is this milestone's operator surface |

## Review notes and execution handoff

This is a design/implementation plan, not a completed compatibility spike or compiled code listing. Java snippets identify core contracts and behavioral tests; imports and framework test wiring follow the verified dependency baseline. The first task fails visibly if that baseline is incompatible. Every task must run its behavior checks before committing, and the full milestone requires actual local completion plus process-level recovery evidence.

Review the sandbox/workspace design, budgets, task contract and supporting Temporal/Spring AI choices before implementation. On approval, use `executing-plans` for inline sequential execution, or `subagent-driven-development` only if parallel agent work is explicitly chosen. Do not begin implementation simply because this plan was written.
