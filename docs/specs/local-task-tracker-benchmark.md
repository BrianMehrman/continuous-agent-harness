# Local task-tracker coding benchmark

Status: Target and local-first scope accepted; implementation design for review

Date: 2026-09-20

## Outcome and scope

The first live benchmark asks a local model to implement a Java task-tracker CLI from a starter repository. The harness must let it inspect files, write source and tests, build, see failures, revise, and submit a working artifact. Independent executable checks decide success. A prose completion message or agent-authored test suite does not establish correctness.

This specification supersedes the incident-report demonstration and its pure-tool restriction for the first coding milestone. Keep the incident report only as an optional small infrastructure fixture. The accepted backend remains Java/Spring Boot. The plan uses the proposed Spring AI/Temporal foundation; these supporting choices remain subject to design review, not a claim that they were separately accepted.

Local inference is the first live integration. Remote-provider implementation and parity evidence move to a subsequent milestone. Preserve a model adapter boundary, but do not scaffold unused remote adapters. The first slice is operable through a loopback API and a repository command-line client. The React console follows under its own plan; API completion must not be reported as completion of the original browser criterion P1-15.

## Task presented to the agent

Implement `example.tasktracker.TaskTracker`, runnable as:

```text
java -jar build/libs/task-tracker.jar --data-dir /data add "Buy milk"
java -jar build/libs/task-tracker.jar --data-dir /data list
java -jar build/libs/task-tracker.jar --data-dir /data complete 1
```

The starter provides a locked Gradle build, an empty class with `main`, and `REQUIREMENTS.md`. It contains no task-tracker implementation or evaluator assertions. The agent writes production Java, JUnit tests, and a usage README. No third-party production libraries are required; Java 21 standard APIs suffice. The locked build supplies a pinned JUnit test dependency and executable JAR manifest.

| Behavior | Exact contract |
|---|---|
| `add DESCRIPTION` | One nonblank argument; trim surrounding whitespace, preserve interior spaces and Unicode; reject tabs, CR, LF and NUL. Print a positive decimal ID and newline; exit 0 |
| ID allocation | First task is 1; increase by 1 for each successful add; never change an existing ID; duplicate descriptions are allowed |
| `list` | Print `ID<TAB>STATUS<TAB>DESCRIPTION` (`STATUS` is `OPEN` or `DONE`) plus newline for each task in ascending ID order; empty store prints nothing; exit 0 |
| `complete ID` | Positive decimal ID of an existing task; print `ID<TAB>DONE` and newline; exit 0; repeating completion is successful and leaves one task |
| Persistence | Separate processes using the same directory see prior tasks and status; different directories are independent; create a missing data directory |
| Invalid input | Unknown/missing command, wrong argument count, missing/invalid directory option, blank/control-character description, malformed/nonpositive/unknown ID: nonempty stderr, empty stdout, exit 2, no changes to existing task data |
| Storage failure | Unwritable directory or failed persistence: nonempty stderr, empty stdout, exit 1; never claim a successful update before persistence succeeds |
| Tests/docs | Include agent-authored behavioral tests and README examples for add/list/complete, persistence, and error exits |

Storage format is an implementation choice. No concurrent writers, editing/deleting tasks, timestamps, HTTP server, database dependency, authentication, or UI is required. The evaluator does not test undocumented behavior or inspect private implementation details. README completeness is recorded separately from executable correctness; initial automated checks require nonempty README and at least one discovered agent test, without pretending to judge documentation quality.

## Completion evaluator

The evaluator lives outside the model-accessible workspace. It compiles a frozen submitted source snapshot using the trusted build, runs agent tests, and exercises the resulting JAR through separately launched processes. It owns the assertions, data directories, expected outputs, and report. Model code never supplies the pass/fail verdict. It is not sufficient to parse a candidate-generated JUnit XML file.

Acceptance covers empty listing; sequential adds; stable IDs; completion and repeated completion; persistence across process launches; two independent data directories; spaces and Unicode; duplicate descriptions; invalid commands/IDs/argument counts/descriptions; unchanged state after invalid input; and failure on an unwritable data directory. Generate deterministic test values from a recorded evaluator seed so hardcoded sample output cannot pass. Every tested rule is in REQUIREMENTS.md; only concrete evaluation cases and implementation are withheld.

Run acceptance commands inside a fresh, network-disabled container with the candidate JAR read-only and a writable disposable data directory. The evaluator controller remains outside that container. Inspect process exit and output from the container runtime, not a candidate-authored status file. Each process has a timeout; a hanging candidate fails with evidence. The build environment is separately disposable, so a candidate test cannot rewrite the evaluator or a reused build cache. Record artifact/source digests and evaluator version/seed with the result.

Do not ship a reference solution into the agent workspace or prompt. Private harness test fixtures may contain a known-correct implementation and deliberately broken variants to prove the evaluator detects real failures. Those fixtures must never be mounted into candidate containers.

## Workspace and tools

Use immutable workspace snapshots in application PostgreSQL, not a shared mutable checkout. A snapshot maps normalized relative paths to UTF-8 content, has a canonical content hash, and references its parent. The Workflow keeps only snapshot IDs and compact tool receipts; application blobs hold files, bounded logs, and conversation payloads. This first benchmark caps source at 1 MiB total, 64 KiB per file, and 64 files. Snapshot/blob retention cannot remove data referenced by active runs.

Writable paths are `src/main/java/**/*.java`, `src/test/java/**/*.java`, and `README.md`. The starter build and REQUIREMENTS.md are readable but immutable. Reject absolute paths, `..`, backslashes, control characters, symlinks and nonregular entries. There is no delete or arbitrary shell tool in this first version.

| Tool | Input | Result |
|---|---|---|
| `list_files` | Snapshot ID, optional relative prefix | Bounded sorted path list |
| `read_file` | Snapshot ID, relative path | Content and SHA-256; explicit too-large/error response |
| `write_file` | Snapshot ID, path, expected file hash or absent marker, replacement content | New immutable snapshot ID and digest |
| `run_tests` | Snapshot ID | Trusted Gradle test command exit, bounded logs, test count, report references |
| `build` | Snapshot ID | Trusted Gradle jar command exit, bounded logs, candidate JAR reference |
| `submit` | Snapshot ID, brief summary | Independent evaluator verdict for that immutable snapshot |

The harness injects run/invocation identity and the current snapshot. The model does not choose another run's IDs. Schema/capability checks precede execution. Invalid arguments become structured errors within the bounded loop; denied operations never execute. A submission failure returns sanitized case-level diagnostics, so the agent can revise within its remaining budget. A passing evaluator result ends the run. Ordinary text without a submit request does not establish completion; provide one protocol reminder, then fail repeated non-action responses.

File writes are pure snapshot transformations plus idempotent database receipt commits. The same invocation ID and input digest must return the same snapshot. A conflicting repeated ID fails. A stale Activity cannot advance the Workflow's snapshot head. Commands build/test/evaluate disposable copies; generated files never mutate source snapshots.

## Isolated execution and recovery

A trusted runner component owns Docker operations and an invocation ledger. It runs separately from the Temporal worker so worker restarts do not remove its deadline/cancellation supervision. It can be another Spring Boot process profile from the same artifact, not a separate distributed product.

Before creating a container, commit its stable invocation ID, input hash, image digest, operation, and deadline. Derive a unique container name from the ID and verify matching labels before attaching to an existing one. Use `create`, stage only the snapshot, then `start`; recovering a created container repeats staging before start. Retain stopped containers until bounded output/artifacts and the exit status are durably recorded. Recover by inspecting the same container; do not blindly start another process. If evidence was lost, record an uncertain attempt before retrying in a fresh disposable environment. No build command has access to external side effects.

Use non-root containers, no network, a read-only root filesystem, bounded scratch storage, 2 CPU / 1 GiB memory / 128-process limits, dropped capabilities, no-new-privileges, and no mounts for host source, home, credentials, or Docker socket. The trusted host runner can contact Docker; generated code cannot. Preload build dependencies into a pinned runner image; never run an agent-edited Gradle build, settings, wrapper, lockfile, or init script or download dependencies during a run. The runner image must contain the pinned Gradle distribution, JDK, and dependency caches, validated by actual offline build and test invocations. Use bounded per-invocation writable Gradle user-home, project-cache, and output directories; do not mount host caches. The trusted build produces `build/libs/task-tracker.jar` and test reports under `build/test-results/test/`. Cap captured output at 256 KiB per invocation with explicit truncation metadata.

A named-container reconciliation loop enforces deadlines and cancellation even while the Temporal worker is down. If the runner restarts, it scans only its owned labels and resumes supervision before accepting new requests. Docker unavailability prevents new execution and is reported; do not claim workloads are killed until the daemon confirms they stopped. Cancellation becomes terminal only after outstanding owned processes are stopped or a visible operational failure is recorded.

## Local model profile and bounds

Selected local server: Ollama. Default endpoint: `http://127.0.0.1:11434`. The earlier environment inspection found Docker, Java 21, Ollama, and installed local models. Builds now use the pinned Gradle wrapper. No inference benchmark was run during planning. Require an explicit `HARNESS_OLLAMA_MODEL` at live-run setup, inspect installed model metadata, and freeze its tag plus resolved full digest in a profile revision. Reject known cloud-backed models and missing local weights; never auto-pull or fall back to cloud. A locally listening Ollama server does not by itself prove inference is local.

Use Spring AI's low-level ChatModel API for one request per Activity, with native tool schemas and normalized response handling. Do not attach a framework agent loop. Test tool-call argument round-tripping, multiple tool calls, tool-result correlation, usage absence, context overflow and timeout behavior against an HTTP fixture server; require a live tool capability probe for the selected installed model before a coding run. Capture the effective context/generation settings and software versions with each run. Temperature 0 is a baseline setting, not a determinism guarantee.

Coding-profile defaults: 40 logical model turns, 100 tool calls, 180 seconds per model attempt, 120 seconds per build/test command, 180 seconds per evaluation, 10 seconds per file operation, 30 minutes total after execution starts. All are frozen before start and visible. Allow at most two infrastructure model attempts per logical turn, at most two runner attempts per logical invocation, and no nested SDK retries. Maintain a separate hard cap of 80 dispatched model attempts; record attempts with unknown outcomes. The absolute deadline continues across downtime. Stop before dispatch if remaining time is insufficient; outstanding operations get a deadline no later than the run deadline.

Do not reuse the incident benchmark's 12-call/10-minute limits. Model context is bounded by the selected profile, with explicit overflow failure and no silent truncation. Large tool logs use capped summaries and artifact references. Infrastructure replay uses recorded results; fresh model re-execution after uncertain completion can produce a different result and must be identified.

## Acceptance matrix

| ID | Required evidence |
|---|---|
| C1 | Starter contains no solution; seeded independent evaluator passes a good fixture and rejects broken persistence, hardcoded output, invalid-ID mutation, hangs and false test reports |
| C2 | Model can inspect/write source and tests, observe a failing build/test, repair and submit; nonpassing submission is never reported successful |
| C3 | Actual installed local model completes an evaluated task; record model digest, task/evaluator version, source/JAR digests, run ID, limits, timing and usage availability |
| C4 | File boundaries, immutable starter, runner restrictions, log limits, workspace quotas and lack of evaluator access are enforced by code |
| C5 | Kill worker after a durable source edit; same run resumes with that snapshot and does not repeat completed calls |
| C6 | Kill worker during build/test; replacement attaches to the same invocation/container and records the result without duplicating the command |
| C7 | Crash runner at create/start/exit/result-commit boundaries; reconciliation preserves bounded execution and labels uncertainty when evidence is lost |
| C8 | Deploy v2 while v1 run is active; existing run remains pinned to available v1 workers, new run uses v2; rollback routes new work back and retains workers needed by active runs |
| C9 | Restart Temporal with persistent service storage; same run resumes; elapsed deadlines remain elapsed |
| C10 | Repeated start/cancel delivery and lost acknowledgments do not duplicate runs/effects; stale projections converge and cannot authorize commands |
| C11 | Cancellation, quotas and deadline stop subsequent model/tool calls; active containers are stopped; late success cannot overwrite applied cancellation |
| C12 | Sentinel credentials do not appear in model history exports, logs or candidate environment; telemetry failure does not prevent execution or queries |

Scripted-model tests prove harness behavior deterministically. A live model's inability to finish is a benchmark failure, not proof of a broken recovery engine. Conversely, a correct JAR does not prove crash recovery. Report both separately. The milestone is complete only when the required local live evidence and recovery checks exist; do not substitute mocks for C3.

## Delivery boundary

The accompanying [implementation plan](../plans/2026-09-20-local-task-tracker-implementation.md) delivers this local API/CLI slice. Browser UI and remote-provider comparison remain subsequent milestones. Local deployment means the API/worker/runner are on the same trusted machine with persistent PostgreSQL and Temporal data; recovery from permanent disk loss or moving to another machine is not claimed.
