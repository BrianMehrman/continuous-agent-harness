# Working-Memory Context Design

Date: 2026-10-17
Status: Approved conversational design; written spec awaiting owner review

## Purpose

Let a local model work on one coding task for many turns without exhausting its context window, and carry that work through to a passing host-owned evaluation of the `task-tracker-v1` benchmark.

The two 2026-10-13 baseline runs failed because the model-visible conversation grew without bound ([baseline evidence](../../evidence/local-task-tracker.md)). `RunActivitiesImpl.appendConversation` kept every assistant tool argument, including whole-file writes, and up to 16 KiB of raw runner log per check. At 16K and 32K context the final replies filled the window, ended `finishReason=length` without a tool call, and the workflow's one-reminder rule ended each run `FAILED`. Raising context only delayed that.

The owner chose to make the conversation blob itself a curated **working memory**: each turn is folded into a structured hand-off that records what the next turn needs, so a run can continue for a long time. Full evidence is not lost, because it already lives in durable receipts outside the conversation blob.

## Success criteria and scope

Success is a trusted evaluator pass (`passed=true`) for the submitted snapshot of a real local Ollama run, followed by an evaluator rerun on the retrieved JAR and source snapshot in a fresh environment. Scripted runs and repository-task runs do not count.

If a live run still fails after the context change, this work may also change:

1. Prompt and protocol wording, built only from public material (`REQUIREMENTS.md` and the tool definitions).
2. Tool result shapes.
3. The installed local model, through a new frozen profile. No model download without owner approval.

Out of scope: raising run limits, new tools, a model-written compaction call, `scripts/local_acceptance.py`, the `liveLocalTest` Gradle task, and the worker-crash and deployment scenarios of Task 10. Those remain open Task 10 items.

## Why compacting the conversation blob is safe

The conversation blob is not the only record of a run:

- Every model reply, including full `write_file` arguments, is stored as the `model_attempt` response blob, and its request blob records the `ModelCall`, including the conversation blob ID the model saw.
- Every snapshot stores its full file contents (`workspace_snapshot`), and write receipts link parent and result snapshots.
- Runner receipts keep exit status, the full log blob, and artifacts.
- `read_file` and `list_files` results are deterministic functions of the snapshot they ran against.

A complete transcript can therefore be rebuilt from receipts. The working memory records the receipt identities of everything it compacts so that trace is explicit.

## Conversation format v2

```json
{
  "version": 2,
  "budget": {"contextTokens": 16384, "outputTokens": 4096, "maxInputTokens": 11059},
  "system": "fixed instructions",
  "task": "REQUIREMENTS.md or repository task text; never compacted",
  "state": {
    "turn": 14,
    "files": [{"path": "src/...", "sha256": "...", "written": true, "turn": 13}],
    "lastCheck": {"tool": "build", "outcome": "FAILED", "exitCode": 1,
                  "errors": ["TaskTracker.java:42: error: cannot find symbol", "..."],
                  "receiptRef": "run-...:turn-13:tool-2"},
    "rejected": [{"turn": 12, "tool": "write_file", "code": "INVALID_ARGUMENTS", "detail": "..."}],
    "note": {"turn": 14, "progress": "...", "next": "..."}
  },
  "recent": ["last K turns as verbatim v1-style messages"],
  "compacted": {"turns": 12, "modelAttempts": ["run-...:turn-1", "..."]}
}
```

- `budget` is computed once by `startConversation` from the run's frozen profile: `maxInputTokens = (contextTokens - outputTokens) * 0.9`. A later profile edit cannot change a run in progress.
- `state.files` lists only files this run has read or written, with the latest hash a `read_file` or `write_file` result reported, whether the run wrote it, and the turn it was last touched. It never contains file contents, and it does not list the whole snapshot; the model uses `list_files` for that. This keeps the state small for repository runs and lets folding stay pure, without workspace access.
- `state.lastCheck.errors` holds at most 20 lines. `state.rejected` keeps the 5 most recent entries.
- `state.note` is the model's latest `Progress:` / `Next:` text, each capped so the note is at most 1.5 KB. It is updated from every turn, not only when that turn is folded.
- `recent` keeps the last K = 2 turns. A turn is the assistant message plus its paired tool results, or the assistant message plus the protocol reminder when it called no tool.
- `compacted.modelAttempts` lists the invocation IDs of folded turns. Each resolves to its `model_attempt` request and response receipts.

## Folding and budget rules

`appendConversation(previous, reply, outcomes)` for a v2 blob:

1. Append the new turn to `recent` and update `state.turn`, `state.files`, `state.lastCheck`, `state.rejected`, and `state.note` from that turn's reply and outcomes.
2. While `recent` holds more than K turns, remove the oldest turn and add its invocation ID to `compacted`.
3. Estimate the rendered size as UTF-8 characters / 3. While it exceeds `budget.maxInputTokens`, apply in order:
   1. Reduce K down to 1.
   2. Replace `write_file` `content` arguments in `recent` with `[written: path, sha256, N bytes]`.
   3. Cut long `read_file` result contents in `recent` to head and tail with an elision marker.
4. If still over budget, fail with `MODEL_CONTEXT_OVERFLOW`; the workflow ends the run `CONTEXT_OVERFLOW` as it does today.

The function is pure: the same previous blob, reply, and outcomes produce identical bytes and therefore the same content-addressed blob ID. A retried or redelivered activity creates no divergent state.

A v1 blob is continued as v1 with today's behavior. Only conversations started by the new `startConversation` are v2.

## Rendering for the model

`OllamaModelAdapter` renders a v2 blob as:

1. `system`
2. `user`: `task`
3. `user`: the working state as compact text headed `Working state (turn N)`, including `compacted.turns`
4. `recent`, with tool calls and tool results paired exactly as v1 requires

The v1 parser remains for runs started on older deployments.

## Tool result shapes

- `write_file` returns `{status, path, sha256, snapshotSha256}`, where `sha256` is the written file's hash.
- `build` and `run_tests` return `{status, exitCode, logBlobId, artifactBlobId, uncertain, errors, logTail, truncated}`. `errors` holds the first 20 lines matching javac `File.java:N: error:` or test-failure patterns; `logTail` is the last 4 KiB of the log. The full log stays in the runner receipt's log blob.
- Error results add a short `detail`, such as the current file hash on an `expectedSha256` mismatch or the missing path on `FILE_NOT_FOUND`.

The evaluator's submit result is unchanged. Repository console runs receive the same tool result shapes.

## Prompt and protocol

The benchmark system prompt states, using public material only:

- Every reply must call at least one tool.
- Read a file before overwriting it, and use the `sha256` from the latest read or write.
- After writing, run `build`, fix reported errors, then run `run_tests`; submit after both pass.
- End each reply's text with `Progress: ...` and `Next: ...`, one or two lines each.
- Older turns are compacted into the working state; treat it as authoritative and reread files when their contents are needed.

The repository task prompt gains only the protocol rules. The reminder after a reply without a tool call restates the latest `Next:` and the last check's errors. The workflow rule is unchanged: one reminder, then `FAILED`.

## Components and compatibility

- `execution/WorkingMemory` (new, pure, no I/O): start, fold, budget enforcement, note extraction, and error-line extraction helpers used by the tool router.
- `RunActivitiesImpl`: `startConversation` loads the frozen profile and writes v2; `appendConversation` delegates to `WorkingMemory` for v2 blobs and keeps the v1 path.
- `OllamaModelAdapter.messages()`: adds v2 rendering beside the v1 parser.
- `ToolRouter`: the result shapes above.
- `CodingWorkflowImpl`, activity signatures, migrations, and the operator API are unchanged. No other component reads conversation blobs (checked with a repository search on 2026-10-17). Workflow replay is unaffected because only activity results change, and Temporal records those.

## Testing

Per the owner's direction, add as few tests as possible: extend existing tests first, add a new test method only when nothing existing covers the behavior, and replace assertions the change makes obsolete. No crash, race, recovery, or deployment tests.

| Behavior | Where | New test methods |
|---|---|---|
| Folding, notes, budget steps, compacted trace, determinism, v1 continuation | New `WorkingMemoryTest`: one long-session test (about 20 turns, large writes, a failing check, a rejected write, notes) under a small budget | 1 |
| Unrecoverable overflow returns `MODEL_CONTEXT_OVERFLOW` | `WorkingMemoryTest` | 1 |
| v2 rendering and pairing | Existing `OllamaAdapterTest` message test | 0 |
| `write_file` hash; check `errors` and `logTail`; mismatch `detail` | Existing `ToolRouterTest` cases | 0 |
| Every recorded model call stays under budget end to end | Existing `CodingWorkflowSmokeIT` scripted run | 0 |

Net new test methods: 2.

## Live iteration

1. Run 1 uses the failed baseline's model and context (`qwen3.5:9b`, 16,384 tokens, 4,096 output, temperature 0) with a new frozen profile, so only the harness differs.
2. Each later run states its hypothesis before starting and changes one allowed lever.
3. On a pass, retrieve the JAR and source snapshot through the artifact API and rerun the evaluator in a fresh environment.
4. After three consecutive failed live runs, stop and report evidence and the next hypothesis to the owner.
5. Record each run in [the baseline evidence](../../evidence/local-task-tracker.md): run ID, commit, profile, per-turn input/output tokens, rendered working-memory size, and terminal reason. Stop task-owned services afterward and preserve database volumes.

## Delivery

1. PR 1: working memory, tool result shapes, prompt changes, their tests, a passing `./gradlew build`, and Run 1 evidence whatever its outcome.
2. Follow-up PRs: one per iteration that changes code, each with its run evidence. The final PR records the pass and updates [the project handoff](../../STATUS.md).
