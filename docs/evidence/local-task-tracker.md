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
