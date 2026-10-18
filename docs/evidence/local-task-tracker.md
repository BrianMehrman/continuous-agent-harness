# Live local task-tracker baseline: failed runs

Date: 2026-10-13 CDT / 2026-10-14 UTC. These are real local Ollama runs against the fresh `task-tracker-v1` starter, **not benchmark acceptance**.

## Setup

- Application commit `45b0afb7b953a8fe9cb79bc3e621befa84b457b3`; packaged JAR SHA-256 `311502b771dc9bc7c5075002a6c13523142dc71ea41959a75ca7dcc2fddd6073`.
- Dedicated PostgreSQL schema `baseline_run` and Temporal namespace `baseline-trial` on the task-owned `harness-tryout` Compose project. The worker was selected as the current `coding-harness` deployment version for that namespace.
- Installed local Ollama 0.40.3 model `qwen3.5:9b`, full digest `6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7`. The read-only tool round-trip probe passed before each profile was saved. Both profiles used 4,096 output tokens and temperature 0; only context size changed.
- Trusted benchmark runner image `sha256:3cd2d727f58b1cce4c2be07d956e0ab9dc7d49e0e42134bcf4883d0e5a8f3d43`. All candidate execution stayed in network-disabled runner containers. No task-owned candidate containers remained afterward.

| Context | Frozen profile | Run ID | Accepted → terminal (UTC) | Result |
|---|---|---|---|---|
| 16,384 | `ollama-26b3aaac3a857d206307c8cd469c75951c047c00eaa7c729318d07853ac3b129` | `run-ab6cff84910d41effbd53e94de0906204cf3dd76246505c268fd1b062f4353be` | 01:49:47 → 01:53:52 | `FAILED` |
| 32,768 | `ollama-5bb34ac74e75dc6f1510ba710208e5a827165b4ea36df988b2b6d637971ba89f` | `run-79e5ff9005c741a944d904bd69e41ee6043009eff35b587ebfbac3960c79f438` | 01:56:34 → 02:07:50 | `FAILED` |

Both runs froze seed snapshot `e7e7f1694b44eb06604938cd3ca75379ed9441e994489986dc920f92b3d241e8` and ended at snapshot `9bdb66caeea463480ca0c6e810b64398b13c2ff45144a8c6ba39d085a1b0f7fd`. The first recorded 12 model replies, two successful write receipts, one failed `TEST` receipt, and no evaluator row. The second recorded 21 model replies, four successful write receipts, failed `TEST` and `BUILD` receipts, and no evaluator row. Neither model submitted a candidate; there is no trusted evaluator verdict or downloadable successful JAR to claim.

## Diagnosis

The first trusted test failed compilation with 19 errors in model-written `TaskTracker.java`. It referenced missing `ExitException`, `Task#setStatus`, `TaskStore#fromJson`, and `FileOutputStream#getPath`, and assigned `TaskStore#add` to an `int` although it returns `Task`. The failed test log is run artifact `b14c429de44ef3cfd1ccc944afa560be6bb555dc0968f75d61515f436e8d82b1`. The 32K comparison reproduced those compile errors in its later `BUILD` receipt, artifact `d9d4f47e772203dfcbb1a0348b450205ddbbd9529e3570ec91fdeb099367f498`. The host runner reported actual exit code 1 in both cases; this was candidate code failure, not a runner startup failure.

The 16K run's turns 11 and 12 had 15,664/720 and 15,737/647 input/output tokens. Each pair exactly filled the 16,384-token context, ended with `finishReason=length`, and contained no tool call. The workflow's one protocol reminder was followed by another empty-tool reply, so it terminally marked the run `FAILED` under `CodingWorkflowImpl`'s explicit rule.

The 32K profile tested that cause: turn 11 used 15,664 input and 1,650 output tokens, ended `stop`, and called a tool; turn 12 also called a tool. It then continued repairing. One write was rejected as `INVALID_ARGUMENTS` after the model supplied an incorrect expected file hash; it read the file, supplied the actual hash, and the next write succeeded. Later writes recorded the same final snapshot digest and did not repair the compile errors. Turns 20 and 21 reached 32,194/574 and 32,276/492 tokens, exactly filling 32,768 each time. Both ended `length` without a tool call, causing the same terminal rule.

The turn-21 durable conversation contained about 84,784 JSON characters of assistant messages and 53,141 of tool messages. `RunActivitiesImpl.appendConversation` retains every assistant tool argument, including repeated full source-file writes, and runner tool results include up to 16 KiB of raw log text. This is a concrete cause of context growth. Raising context moved the failure from turn 11 to turn 20 but did not produce a compiling candidate. Model code quality and context handling are separate problems; neither run proves the other would disappear if only one changed.

## Next work and limits

Design a bounded model-visible conversation that keeps the durable full transcript for evidence while retaining the current task, recent file hashes, check failures, and enough repair context for the next call. Verify the reduction with one focused workflow check, then run a new baseline with a fresh frozen profile and compare receipts. If compilation still fails after context is controlled, inspect the model's API assumptions or select a stronger installed local model. Avoid another inference run that changes only context size without a new hypothesis.

The existing `harness-tryout` Compose services and three Java roles were stopped after both runs; named database volumes and the `baseline_run` schema were preserved. No recovery or deployment scenario was run here. The `local_acceptance.py` script, opt-in live test task, and the rest of Task 10 remain open.


## Working-memory Run 1: context bounded, turn limit reached

Date: 2026-10-17 UTC. A real local Ollama run against the fresh `task-tracker-v1` starter, **not benchmark acceptance**. Design: [working-memory context spec](../superpowers/specs/2026-10-17-working-memory-context-design.md).

**Hypothesis:** with the model-visible conversation bounded, `qwen3.5:9b` at 16,384 context no longer ends in `finishReason=length` replies without tool calls, and every turn's input stays at or below the budget (`maxInputTokens` 11,059).

### Setup

- Application commit `42dc16fc256c5c73728df6820dcc60e267b29161`; packaged JAR SHA-256 `435930a6f02e6317813f164d61247e3b1d3dca6d56dff0f0f06a3fc01c9b99d4`. `./gradlew build` passed on that commit on 2026-10-17 (60 unit, 18 integration tests; the opt-in `LocalOllamaProbeIT` skipped).
- Compose project `harness-tryout`, PostgreSQL schema `working_memory_run1`, Temporal namespace `wm-run1`, worker build selected as current `coding-harness` version for that namespace.
- Ollama 0.40.3, `qwen3.5:9b`, full digest `6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7`. The read-only tool probe passed. Profile `ollama-26b3aaac3a857d206307c8cd469c75951c047c00eaa7c729318d07853ac3b129` (16,384 context, 4,096 output, temperature 0) is the same frozen profile as the earlier 16K baseline, because profile IDs derive from the digest and settings; only the harness code differs.
- Trusted benchmark runner image `sha256:3cd2d727f58b1cce4c2be07d956e0ab9dc7d49e0e42134bcf4883d0e5a8f3d43`. All nine runner invocations recorded `cleaned=true`.

| Run ID | Accepted → terminal (UTC) | Result |
|---|---|---|
| `run-75e16c7c0fa0e64bccb810424bee7433d8072cc68f6125a3549daab4cef1f2c9` | 16:07:40 → 16:20:16 | `LIMIT_EXHAUSTED` (40 model turns) |

### Per-turn model usage

`Compacted` is the number of earlier turns folded into the working state in the conversation that turn's call received. Every call made one recorded attempt.

| Turn | Input | Output | Finish | Compacted | Tools |
|---:|---:|---:|---|---:|---|
| 1 | 1,462 | 42 | stop | 0 | list_files |
| 2 | 1,622 | 88 | stop | 0 | read_file, read_file, read_file |
| 3 | 3,143 | 1,135 | stop | 0 | write_file |
| 4 | 4,305 | 972 | stop | 1 | write_file |
| 5 | 4,151 | 2,072 | stop | 2 | write_file |
| 6 | 5,169 | 376 | stop | 3 | write_file |
| 7 | 4,626 | 22 | stop | 4 | build |
| 8 | 3,359 | 56 | stop | 5 | read_file |
| 9 | 3,867 | 984 | stop | 6 | write_file |
| 10 | 4,161 | 22 | stop | 7 | build |
| 11 | 3,484 | 15 | stop | 8 | run_tests |
| 12 | 3,712 | 121 | stop | 9 | read_file, read_file |
| 13 | 6,701 | 218 | stop | 10 | write_file |
| 14 | 6,022 | 958 | stop | 11 | write_file |
| 15 | 3,681 | 79 | stop | 12 | read_file |
| 16 | 5,742 | 989 | stop | 13 | write_file |
| 17 | 5,773 | 1,484 | stop | 14 | write_file |
| 18 | 4,981 | 1,779 | stop | 15 | write_file |
| 19 | 5,785 | 15 | stop | 16 | run_tests |
| 20 | 4,219 | 49 | stop | 17 | read_file |
| 21 | 4,217 | 55 | stop | 18 | read_file |
| 22 | 5,742 | 43 | stop | 19 | read_file |
| 23 | 4,004 | 50 | stop | 20 | read_file |
| 24 | 4,370 | 74 | stop | 21 | read_file |
| 25 | 5,762 | 42 | stop | 22 | read_file |
| 26 | 4,022 | 50 | stop | 23 | read_file |
| 27 | 4,369 | 100 | stop | 24 | read_file |
| 28 | 5,788 | 345 | stop | 25 | build |
| 29 | 4,634 | 95 | stop | 26 | read_file |
| 30 | 3,268 | 224 | stop | 27 | write_file |
| 31 | 2,705 | 22 | stop | 28 | build |
| 32 | 4,064 | 70 | stop | 29 | read_file |
| 33 | 5,274 | 1,542 | stop | 30 | write_file |
| 34 | 5,655 | 22 | stop | 31 | build |
| 35 | 4,160 | 15 | stop | 32 | run_tests |
| 36 | 3,377 | 71 | stop | 33 | read_file |
| 37 | 4,879 | 1,877 | stop | 34 | write_file |
| 38 | 6,114 | 26 | stop | 35 | build |
| 39 | 4,499 | 15 | stop | 36 | run_tests |
| 40 | 4,144 | 102 | stop | 37 | read_file |

### Diagnosis

The hypothesis held. Input stayed between 1,462 and 6,701 tokens, well under the 11,059-token budget; no reply ended `length`; every reply called at least one tool. The run no longer fails on context. It ran out of its 40 model turns without submitting, so there is no evaluator verdict.

Checks: `BUILD` failed at turns 7, 28 and 31 and succeeded at turns 10, 34 and 38; `TEST` failed at turns 11, 35 and 39. The production code compiled by turn 10; the failures were compile errors in the model's own `TaskTrackerTest.java`, which the requirements oblige it to write. The last check (turn 39) reported `cannot find symbol` at four test lines and an `Integer.compare` misuse.

Three causes from the receipts:

1. **Extracted errors drop javac's context lines.** `errors` keeps only the `File.java:N: error:` line. For `cannot find symbol`, javac puts the missing symbol and its location on the following `symbol:` and `location:` lines, so the model saw which line failed but not what was missing. Gradle also prints each error twice, so half the 20 lines were duplicates. After the turn holding the raw `logTail` was compacted, the model could not recover that detail.
2. **Rereading instead of fixing.** Turns 20–27 were eight consecutive `read_file` calls cycling over `TaskTracker.java`, `Task.java` and the test file. With K = 2, file contents read two turns earlier are gone, and the errors did not say what to look for.
3. **No hand-off notes.** The model never wrote the requested `Progress:`/`Next:` lines, so `state.note` stayed empty and nothing carried its intent across compaction.

A harness defect also cost one turn: the turn-19 `run_tests` call was rejected with `Deadline must be within 120 seconds` (`JdbcRunner`). The workflow sets the tool deadline from Temporal workflow time, and the runner validates it against the worker host clock; a clock skew between the Temporal container and the host can push the deadline past the runner's 120-second bound. It predates this change and is recorded here for a separate fix.

### Next hypothesis (Run 2)

Lever: **tool result shape.** If each extracted error keeps javac's following `symbol:`/`location:` lines and duplicate errors are removed, the model can fix test compile errors from the working state without reread loops, so it reaches a passing `run_tests` and submits within 40 turns. Keep the model, profile and prompt unchanged.

The Java roles and `harness-tryout` services were stopped after the run; the named database volume and the `working_memory_run1` schema were preserved.

## Working-memory Run 2: compiler error context

**Hypothesis (written before the run):** Run 1's extracted errors kept only javac's `File.java:N: error:` line, so `cannot find symbol` arrived without the missing symbol, and Gradle's repeated output filled half the 20 lines with duplicates. If each extracted error carries javac's indented `symbol:`, `location:`, `required:`, `found:` and `reason:` lines (and a failing test carries its first indented assertion line), and exact duplicates are dropped, the model can fix its test compile errors from the working state without reread loops, reach a passing `run_tests`, and submit within 40 turns. Single lever: **tool result shape**. Model, profile (`qwen3.5:9b`, 16,384 context) and prompt are unchanged from Run 1.


### Setup

- Application commit `d7fa4a3`; packaged JAR SHA-256 `f6aafca8b560c48a52c4863c71ba62d65c6ed268bf0487ed792aaf24722af9d8`. `./gradlew build` passed on that commit on 2026-10-17 (60 unit, 18 integration tests; the opt-in `LocalOllamaProbeIT` skipped).
- Compose project `harness-tryout`, PostgreSQL schema `working_memory_run2`, Temporal namespace `wm-run2`, worker build selected as current `coding-harness` version for that namespace.
- Same Ollama 0.40.3, `qwen3.5:9b` digest, and frozen profile `ollama-26b3aaac3a857d206307c8cd469c75951c047c00eaa7c729318d07853ac3b129` as Run 1. The read-only tool probe passed again. All seven runner invocations recorded `cleaned=true`.

| Run ID | Accepted → terminal (UTC) | Result |
|---|---|---|
| `run-cc008fc14373c39b39d4254a3a71021a56085bed89a476b20c3378c7afe74451` | 17:00:59 → 17:15:22 | `LIMIT_EXHAUSTED` (40 model turns) |

### Per-turn model usage

| Turn | Input | Output | Finish | Compacted | Tools |
|---:|---:|---:|---|---:|---|
| 1 | 1,462 | 42 | stop | 0 | list_files |
| 2 | 1,622 | 88 | stop | 0 | read_file, read_file, read_file |
| 3 | 3,143 | 1,209 | stop | 0 | write_file |
| 4 | 4,383 | 1,336 | stop | 1 | write_file |
| 5 | 4,642 | 51 | stop | 2 | read_file |
| 6 | 4,485 | 1,357 | stop | 3 | write_file |
| 7 | 4,543 | 1,719 | stop | 4 | write_file |
| 8 | 5,292 | 23 | stop | 5 | build |
| 9 | 4,686 | 61 | stop | 6 | read_file |
| 10 | 4,290 | 1,343 | stop | 7 | write_file |
| 11 | 4,994 | 22 | stop | 8 | build |
| 12 | 3,884 | 15 | stop | 9 | run_tests |
| 13 | 3,042 | 78 | stop | 10 | read_file |
| 14 | 4,611 | 1,841 | stop | 11 | write_file |
| 15 | 5,979 | 49 | stop | 12 | run_tests |
| 16 | 5,529 | 88 | stop | 13 | read_file |
| 17 | 5,056 | 163 | stop | 14 | read_file |
| 18 | 5,856 | 1,527 | stop | 15 | write_file |
| 19 | 6,025 | 72 | stop | 16 | read_file |
| 20 | 5,563 | 122 | stop | 17 | run_tests |
| 21 | 4,400 | 74 | stop | 18 | read_file |
| 22 | 4,773 | 187 | stop | 19 | read_file |
| 23 | 5,759 | 2,089 | stop | 20 | write_file |
| 24 | 5,984 | 57 | stop | 21 | read_file |
| 25 | 6,226 | 34 | stop | 22 | run_tests |
| 26 | 4,788 | 60 | stop | 23 | read_file |
| 27 | 4,419 | 49 | stop | 24 | read_file |
| 28 | 5,689 | 1,955 | stop | 25 | write_file |
| 29 | 6,150 | 68 | stop | 26 | read_file |
| 30 | 6,126 | 1,851 | stop | 27 | write_file |
| 31 | 6,022 | 65 | stop | 28 | read_file |
| 32 | 6,062 | 1,178 | stop | 29 | write_file |
| 33 | 5,432 | 51 | stop | 30 | read_file |
| 34 | 5,375 | 1,013 | stop | 31 | write_file |
| 35 | 5,261 | 50 | stop | 32 | read_file |
| 36 | 5,303 | 113 | stop | 33 | run_tests |
| 37 | 5,841 | 71 | stop | 34 | read_file |
| 38 | 4,915 | 49 | stop | 35 | read_file |
| 39 | 5,509 | 138 | stop | 36 | list_files |
| 40 | 4,603 | 34 | stop | 37 | read_file |

### Diagnosis

The hypothesis partly held. The production code built at turn 11, and the model's test file stopped failing to compile: from turn 25 every `run_tests` failure was a runtime test failure, not a compile error. Input stayed between 1,462 and 6,226 tokens; no reply ended `length`; every reply called a tool. The run still used all 40 turns without submitting, so there is no evaluator verdict.

Checks: `BUILD` failed at turn 8 and succeeded at turn 11; `TEST` failed at turns 12, 15, 20, 25 and 36. At the end, all 15 of the model's tests failed with the same `java.nio.file.NoSuchFileException` (for example `testAddTask` at `TaskTrackerTest.java:32`): one storage defect, reported with its context line as intended.

New cause from the receipts: **the model misreads the `write_file` result.** The result returns the written file's `sha256` and a `snapshotSha256`. After writes at turns 18, 23, 28 and 30, the model said the hash "doesn't match what I expected" or "didn't change" and spent turns 19, 24, 29, 31 and 33 rereading the file it had just written. Write receipts show each write did produce a new snapshot (turn 30 rewrote identical content). The model again wrote no `Progress:`/`Next:` notes. The turn-limit cost of rereads is about one turn in eight.

The `Deadline must be within 120 seconds` rejection seen in Run 1 did not recur.

### Next hypothesis (Run 3)

Lever: **tool result shape.** If the `write_file` result names its hash as the one to use for the next write of that path, says no reread is needed, and drops the model-irrelevant `snapshotSha256`, the model stops rereading after writes and keeps those turns for fixing its storage defect, reaching a passing `run_tests` and a submit within 40 turns. Model, profile and prompt stay unchanged. Run 3 is the third consecutive failed-run checkpoint if it fails.

The Java roles and `harness-tryout` services were stopped after the run; the named database volume and the `working_memory_run2` schema were preserved.

## Working-memory Run 3: write result shape

Date: 2026-10-17 UTC. A real local Ollama run against the fresh `task-tracker-v1` starter, **not benchmark acceptance**.

**Hypothesis:** see [Run 2's next hypothesis](#next-hypothesis-run-3). The `write_file` result now returns `status`, `path`, `sha256` and `next` ("Use this sha256 as expectedSha256 for the next write_file of <path>; no read_file is needed"); `snapshotSha256` is gone.

### Setup

- Application commit `0e733b1`; packaged JAR SHA-256 `5b04db0bf1af6750ea0cca311039783d40c52225359d54a09b2352d9211a7124`. `./gradlew build` passed on that commit on 2026-10-17 (60 unit, 18 integration tests; the opt-in `LocalOllamaProbeIT` skipped).
- Compose project `harness-tryout`, PostgreSQL schema `working_memory_run3`, Temporal namespace `wm-run3`, worker build selected as current `coding-harness` version for that namespace.
- Same Ollama, `qwen3.5:9b` digest, and frozen profile `ollama-26b3aaac3a857d206307c8cd469c75951c047c00eaa7c729318d07853ac3b129` (16,384 context, 4,096 output, temperature 0) as Runs 1 and 2. The read-only tool probe passed.
- Run `run-7194b630355a2f59e8008dc117f90ea277dd4c9315a57e0d39a908f8c4f0b028`, started 19:39:54, ended `FAILED` at 19:44:21.

### Per-turn model usage

| Turn | Input | Output | Finish | Compacted | Tools |
|---:|---:|---:|---|---:|---|
| 1 | 1,462 | 42 | stop | 0 | list_files |
| 2 | 1,622 | 88 | stop | 0 | read_file ×3 |
| 3 | 3,143 | 2,948 | stop | 0 | write_file |
| 4 | 6,088 | 4,096 | length | 1 | none |
| 5 | 4,835 | 4,096 | length | 2 | none |

### Diagnosis

The hypothesis was **not tested**. The new `write_file` result first reaches the model at turn 4, and the run ended before a second write. Turn 4 replied "Now let me create the test file and README:", then used the whole 4,096-token output cap without completing a tool call. Ollama returned no tool calls and `finishReason=length`. The workflow reminded the model of the protocol once; turn 5 hit the cap again with empty text, so the workflow ended `FAILED` (two replies without tools). No runner invocation, build or evaluation ran.

**Nondeterminism.** Turn 3's input was identical in Runs 2 and 3 (3,143 tokens, before any result shaped by this change). Yet Run 2 wrote a 1,209-token `TaskTracker.java` and Run 3 a 2,948-token one. Temperature 0 does not make these Ollama runs repeatable. A single run cannot attribute an outcome to a one-line result change.

**Output cap.** Working memory bounds input, not output. A single `write_file` (or several writes in one reply) larger than 4,096 output tokens is lost whole, and the second such reply ends the run. Runs 1 and 2 avoided this only by chance.

### Checkpoint

This is the third consecutive failed live run, so iteration stopped for an owner decision. Candidate levers within the agreed limits:

1. **Prompt wording:** tell the model to keep each reply to one file and each file small (split tests across files), and to write `Progress:`/`Next:` notes. This addresses the output cap and the missing notes without raising limits.
2. **Recovery from a capped reply:** when a reply ends `length` without a tool call, the protocol reminder could say the reply was cut at the output limit and to write a smaller file. This changes reminder wording, not limits or tools.
3. **A stronger installed model** (`qwen3.6:27b` or `qwen3.5:27b`): likely better code per turn, but slower; 40 turns may not fit the 30-minute run limit.
4. **Repeat runs:** given nondeterminism, judge each change over two or three runs rather than one.

The Java roles and `harness-tryout` services were stopped after the run; the named database volume and the `working_memory_run3` schema were preserved.

## Working-memory Run 4: one small write per reply

Date: 2026-10-17 UTC. A real local Ollama run against the fresh `task-tracker-v1` starter, **not benchmark acceptance**.

**Hypothesis:** the owner chose checkpoint levers 1 and 2. The protocol now asks for at most one `write_file` per reply, each file under 150 lines, and for `Progress:`/`Next:` lines at the start of each reply. A reply that ends `length` without a tool call gets a reminder saying it was cut off and to write a smaller file. Then no reply is lost to the output cap, notes appear, and the model reaches a passing `run_tests` and a submit within 40 turns.

### Setup

- Application commit `b7a87e8`; packaged JAR SHA-256 `398dc5b47853a0d3c4462bb36aaa8a60b5ad3663ef5a570d4c23e7d0d2a6b51c`. `./gradlew build` passed on that commit on 2026-10-17 (60 unit, 18 integration tests; the opt-in `LocalOllamaProbeIT` skipped).
- Compose project `harness-tryout`, PostgreSQL schema `working_memory_run4`, Temporal namespace `wm-run4`, worker build selected as current `coding-harness` version for that namespace.
- Same Ollama, `qwen3.5:9b` digest, and frozen profile `ollama-26b3aaac3a857d206307c8cd469c75951c047c00eaa7c729318d07853ac3b129` as Runs 1–3. The read-only tool probe passed.
- Run `run-fc99fe31c77ca3567f36f626d65a5101066225f8ca5aea907b31b273ab6bd3b3`, started 20:02:16, ended `LIMIT_EXHAUSTED` at about 20:17:59. All 15 runner invocations were recorded and cleaned.

### Per-turn model usage

| Turn | Input | Output | Finish | Compacted | Tools |
|---:|---:|---:|---|---:|---|
| 1 | 1,511 | 56 | stop | 0 | list_files |
| 2 | 1,725 | 96 | stop | 0 | read_file, read_file |
| 3 | 2,911 | 2,717 | stop | 0 | write_file |
| 4 | 5,644 | 58 | stop | 1 | run_tests |
| 5 | 6,152 | 79 | stop | 2 | read_file |
| 6 | 6,180 | 2,487 | stop | 3 | write_file |
| 7 | 7,534 | 44 | stop | 4 | build |
| 8 | 5,780 | 2,420 | stop | 5 | write_file |
| 9 | 5,744 | 89 | stop | 6 | read_file |
| 10 | 7,103 | 2,343 | stop | 7 | write_file |
| 11 | 7,079 | 50 | stop | 8 | build |
| 12 | 5,278 | 2,310 | stop | 9 | write_file |
| 13 | 5,274 | 41 | stop | 10 | build |
| 14 | 4,673 | 43 | stop | 11 | run_tests |
| 15 | 2,691 | 72 | stop | 12 | list_files |
| 16 | 2,385 | 1,452 | stop | 13 | write_file |
| 17 | 3,592 | 426 | stop | 14 | write_file |
| 18 | 4,037 | 49 | stop | 15 | build |
| 19 | 2,946 | 42 | stop | 16 | run_tests |
| 20 | 4,053 | 132 | stop | 17 | read_file, read_file |
| 21 | 7,665 | 1,570 | stop | 18 | write_file |
| 22 | 4,039 | 66 | stop | 20 | build |
| 23 | 4,145 | 44 | stop | 20 | run_tests |
| 24 | 3,824 | 87 | stop | 21 | read_file |
| 25 | 5,007 | 104 | stop | 22 | read_file |
| 26 | 6,294 | 1,559 | stop | 23 | write_file |
| 27 | 6,282 | 120 | stop | 24 | read_file |
| 28 | 5,576 | 82 | stop | 25 | build |
| 29 | 4,173 | 44 | stop | 26 | run_tests |
| 30 | 3,840 | 114 | stop | 27 | read_file |
| 31 | 5,061 | 1,608 | stop | 28 | write_file |
| 32 | 5,652 | 131 | stop | 29 | read_file |
| 33 | 5,672 | 60 | stop | 30 | build |
| 34 | 4,165 | 43 | stop | 31 | run_tests |
| 35 | 3,819 | 81 | stop | 32 | read_file |
| 36 | 5,022 | 102 | stop | 33 | read_file |
| 37 | 6,309 | 2,469 | stop | 34 | write_file |
| 38 | 7,252 | 113 | stop | 35 | read_file |
| 39 | 6,490 | 139 | stop | 36 | build |
| 40 | 4,272 | 45 | stop | 37 | run_tests |

### Diagnosis

Both levers worked; the run still did not finish. **Notes:** every reply began with `Progress:` and `Next:` lines, the first run where the model wrote them. **Output cap:** no reply ended `length` (largest output 2,717 tokens), so the cut-off reminder never fired. Input stayed between 1,511 and 7,665 tokens.

Checks: `BUILD` failed at turns 7 and 11 and succeeded from turn 13. The model wrote no tests until turn 16, after `run_tests` passed with no test sources at turn 14. From turn 19 every `run_tests` (turns 19, 23, 29, 34, 40) failed in `compileTestJava`. `build` compiles only production code, so it kept succeeding.

Remaining causes, from the receipts:

1. **A test design error the model could not settle.** Its tests try to check CLI argument errors by calling Java methods with the wrong arguments: `tracker.list("extra")`, `tracker.complete()`, `tracker.complete("x")`. javac reported these with full context every time (`required: no arguments / found: String`). Across turns 21–39 the model alternated between "these calls are wrong" and "these are intentional tests for invalid usage" (its turn-39 note), rewrote the file three times, and the same four errors remained at turn 40.
2. **Rereads after writes continued** at turns 9, 27, 32 and 38 (Run 2: five). The model claimed the returned hash "didn't change". It was right once: turn 26 rewrote identical content, and its write receipt shows an unchanged snapshot. It was wrong at turn 32: the turn-31 write changed the file from `5233b8f7…` to `e2796950…`. A 9B model does not compare 64-character hashes reliably, and the `next` hint added in Run 3 did not stop the rereads.

### Next hypotheses

- **Tool result shape:** add `"changed": true|false` to the `write_file` result, computed by the host from the old and new content hash. The model then does not have to compare hashes itself, and an identical rewrite such as turn 26 is named as such.
- **Prompt wording:** add a general testing rule: test error handling through the command-line entry point or the error a method throws, and never call a method with arguments its signature does not accept. This is general Java advice, not evaluator material.
- **Model:** the two remaining causes are reasoning errors, not context or output limits. A larger installed model (`qwen3.6:27b`) is the most direct lever, at the cost of speed against the 30-minute run limit.

This is the fourth consecutive failed live run. Iteration stopped for an owner decision.

The Java roles and `harness-tryout` services were stopped after the run; the named database volume and the `working_memory_run4` schema were preserved.

## Working-memory Run 5: write `changed` flag and test-design rule

Date: 2026-10-17 UTC. A real local Ollama run against the fresh `task-tracker-v1` starter, **not benchmark acceptance**.

**Hypothesis:** the owner chose both Run 4 follow-ups. `write_file` results now carry `changed: true|false`, computed by the host from the old and new content hash. The protocol adds two rules: check errors in tests through the CLI entry point or thrown exceptions, never calling a method with arguments its signature does not accept; and trust `changed` instead of rereading. Then rereads after writes stop, the test-compile loop does not recur, and the model reaches a passing `run_tests` and a submit.

### Setup

- Application commit `e1a1575`; packaged JAR SHA-256 `9d03e974b0343044a983d806d8e782756026183ee3c0d868edd68d5e9eceeaec`. `./gradlew build` passed on that commit on 2026-10-17 (60 unit, 18 integration tests; the opt-in `LocalOllamaProbeIT` skipped).
- Compose project `harness-tryout`, PostgreSQL schema `working_memory_run5`, Temporal namespace `wm-run5`, same model, digest and frozen profile as Runs 1–4. The read-only tool probe passed.
- Run `run-2f4d38dd5227c748e11c80676a5abab71e0a5ef510d6e2dc9c73a687f5a206b7`, started 22:32:47, ended `EXPIRED` at 23:02:48 (30-minute run deadline) during turn 39. All eight runner invocations were recorded and cleaned.

### Per-turn model usage

| Turn | Input | Output | Finish | Compacted | Tools |
|---:|---:|---:|---|---:|---|
| 1 | 1,578 | 24 | stop | 0 | list_files |
| 2 | 1,720 | 98 | stop | 0 | read_file, read_file |
| 3 | 2,950 | 2,440 | stop | 0 | write_file |
| 4 | 5,449 | 54 | stop | 1 | build |
| 5 | 5,591 | 2,428 | stop | 2 | write_file |
| 6 | 5,584 | 50 | stop | 3 | build |
| 7 | 5,098 | 2,401 | stop | 4 | write_file |
| 8 | 5,091 | 45 | stop | 5 | build |
| 9 | 5,074 | 2,350 | stop | 6 | write_file |
| 10 | 5,031 | 45 | stop | 7 | build |
| 11 | 5,374 | 2,333 | stop | 8 | write_file |
| 12 | 5,378 | 86 | stop | 9 | read_file |
| 13 | 6,856 | 2,359 | stop | 10 | write_file |
| 14 | 6,907 | 82 | stop | 11 | read_file |
| 15 | 6,874 | 2,379 | stop | 12 | write_file |
| 16 | 6,943 | 82 | stop | 13 | read_file |
| 17 | 6,894 | 2,403 | stop | 14 | write_file |
| 18 | 6,991 | 82 | stop | 15 | read_file |
| 19 | 6,918 | 2,440 | stop | 16 | write_file |
| 20 | 7,068 | 56 | stop | 17 | build |
| 21 | 5,357 | 2,509 | stop | 18 | write_file |
| 22 | 5,574 | 56 | stop | 19 | build |
| 23 | 5,435 | 229 | stop | 20 | read_file |
| 24 | 5,533 | 2,711 | stop | 21 | write_file |
| 25 | 7,526 | 2,679 | stop | 22 | write_file |
| 26 | 7,674 | 2,541 | stop | 23 | write_file |
| 27 | 7,479 | 2,542 | stop | 24 | write_file |
| 28 | 7,343 | 98 | stop | 25 | read_file |
| 29 | 7,148 | 2,544 | stop | 26 | write_file |
| 30 | 7,186 | 60 | stop | 27 | build |
| 31 | 5,470 | 2,490 | stop | 28 | write_file |
| 32 | 5,469 | 109 | stop | 29 | build |
| 33 | 5,518 | 2,492 | stop | 30 | write_file |
| 34 | 5,521 | 179 | stop | 31 | read_file |
| 35 | 7,264 | 2,454 | stop | 32 | write_file |
| 36 | 7,183 | 2,449 | stop | 33 | write_file |
| 37 | 7,171 | 130 | stop | 34 | read_file |
| 38 | 7,123 | 2,473 | stop | 35 | write_file |

### Diagnosis

The `changed` flag was accurate and the model read it correctly, but the run never got past compiling its production code. From turn 9 every `build` failed with `variable dataDir might not have been initialized` in `TaskTracker.java`. The model rewrote the whole file 20 times, which produced only 11 distinct snapshots: many rewrites were byte-identical. It reported each one correctly ("The write didn't change the file (changed=false)") and then reread and rewrote the same content again. At temperature 0, near-identical input regenerates the same 2,400-token file, so whole-file rewrites cannot escape this error. This is the first run to end on the time limit rather than the turn limit: about 20 rewrites of 2,400–2,700 tokens each consumed the 30 minutes.

Notes appeared every turn, and no reply ended `length`. Input stayed between 1,578 and 7,674 tokens. Neither the test-design rule nor the reread rule was exercised in a useful way, because no test was written.

### Conclusion and direction change

Five live runs on `qwen3.5:9b` have failed. Each change fixed the failure it targeted, and each run then failed for a new reason, now including a single fixed-point loop that a turn or time budget alone cannot recognize. On 2026-10-17 the owner redirected the work: a long-running, plan-driven harness with stall detection, pause-at-limit, role agents (architect, project manager, plan reviewer, developer, QA) on local and remote models, and a UI with human-in-the-loop and agent-loop modes. The benchmark pass follows that work. Further single-model prompt iterations are paused.

The Java roles and `harness-tryout` services were stopped after the run; the named database volume and the `working_memory_run5` schema were preserved.

## M1 acceptance: stall detection and pause-at-limit

Date: 2026-10-18 UTC. A real local Ollama run against the fresh `task-tracker-v1` starter, **not benchmark acceptance**. Design: [M1 spec](../superpowers/specs/2026-10-17-stall-detection-and-pause-design.md).

### Setup

- Application commit `1bc471e` on `feat/m1-stall-detection-and-pause`; packaged JAR SHA-256 `ec9aa49ec4ef70c76179e501a0058e86d4f78123c946e91e2fe8fd5e02e39598`. `./gradlew build` passed on that commit on 2026-10-18 (61 unit, 18 integration tests; the opt-in `LocalOllamaProbeIT` skipped).
- Default Compose project, PostgreSQL schema `m1_pause_run1`, Temporal namespace `m1-pause1`, worker build selected as current `coding-harness` version for that namespace. Same `qwen3.5:9b` digest and frozen profile `ollama-26b3aaac3a857d206307c8cd469c75951c047c00eaa7c729318d07853ac3b129` as the working-memory runs; default pause policy (thresholds 3/3/6, 7-day waiting limit).
- Run `run-d75ef2ece28698e83756cbf20cbf2e5aaf7f62119370c37ab5018cc76e5855ec`, started 00:53:11.

### What happened

1. **Nudge, then pause.** The model built its implementation by turn 11 and wrote tests, which failed at turns 16 and 20. From turn 21 it mostly reread files. At turn 26 the harness recorded `NUDGED` (`NO_NEW_FACTS`). The model wrote one change at turn 27, then returned to rereading. At 01:07:53, turn 33, the run paused: reason `STALLED`, signal `NO_NEW_FACTS`, evidence "no changed write, new check result, or new read since turn 27", allowance 33/40 turns and 34/100 tool calls, 14.6 minutes active. The pause record carried the model's last note ("Progress: Read the test file to understand what's expected. Next: Read the current TaskTracker.java implementation…"). The execution slot was released.
2. **Operator view.** While paused, `GET /api/work` listed the run under `pausedRuns`, and the served console bundle contained the new banner and pause panel. A visual browser check was not performed in this session.
3. **Resume without a hint.** At 01:08:15 `harness.py resume --turns 20` (command `m1-pause1-resume-1`) was delivered and applied; the run requeued, was readmitted, and was `RUNNING` again at 01:08:20. The run is labeled **extended**.
4. **Second pause.** At 01:09:21, turn 39, it paused again with `NO_NEW_FACTS`. Since turn 29 the model had alternated `read_file` of the test file and the implementation with byte-identical inputs (5,048 and 6,148 tokens) while three tests failed. Because a resume without a hint keeps earlier nudges, the next firing paused immediately.
5. **Stop.** `harness.py stop` (command `m1-pause1-stop-1`) ended the run `STALLED`. Event trail: `NUDGED, PAUSED, RESUMED, PAUSED, STOPPED`. All nine runner invocations were recorded and cleaned. No submit, so no evaluator verdict.

### Assessment

M1 behaved as specified: a fixed-point loop that previously burned the remaining turns and minutes (Run 5 used its full 30 minutes) was detected within six turns, paused with evidence, and was resumable and stoppable through durable commands. A budget extension alone did not help this model; the next lever is a hint (assisted) or, per the roadmap, role handoff to a project manager (M4) and stronger models (M2).

Not exercised live: a repository work item paused, resumed and reaching "Ready for review" (covered by `CodingWorkflowTest` and `ApiWorkflowIT`), and the browser view of the banner and pause panel.

The Java roles were stopped after the run; the default database volume, schema `m1_pause_run1` and namespace `m1-pause1` were preserved.

