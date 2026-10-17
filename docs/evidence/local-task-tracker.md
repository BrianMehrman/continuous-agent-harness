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
