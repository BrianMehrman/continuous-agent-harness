# Stall Detection and Pause-at-Limit Design (M1)

Date: 2026-10-17
Status: Approved conversational design; written spec awaiting owner review

## Purpose

Milestone M1 of the [revised roadmap](../../plans/roadmap.md). A coding run must stop wasting its budget when it makes no progress, and must not discard its work when a budget runs out. Today a run ends `LIMIT_EXHAUSTED` or `EXPIRED` however close it was, and the turn count is its only stopping rule. Working-memory Run 5 (2026-10-17) rewrote one file 20 times without changing a single compiler error. The facts proving the stall were recorded from about turn 13, but the run continued until its 30-minute deadline ([evidence](../../evidence/local-task-tracker.md)).

M1 makes two changes:

1. The harness detects stalls from facts it already records and nudges the agent once before pausing.
2. Turn, tool-call, and time limits pause the run instead of ending it. The operator resumes it with extra budget or a hint, or stops it.

**Owner decisions:** the long-running harness comes first and the benchmark pass second; human-in-the-loop and agent-loop modes; hard stops always reach the operator; first-pass notification inside the UI only. M1 serves the human-in-the-loop mode. In M4, stall handoff to the project manager arrives for the agent loop, and its hard stops reuse this pause.

## Scope

In scope: both run kinds that use `CodingWorkflowImpl`, which are the task-tracker benchmark (`/api/runs`) and repository work items (`/api/work`).

Out of scope:
- role handoff (M4)
- remote models and changing the model on resume (M2)
- the mission graph (M3)
- notifications outside the UI
- crash, race, and deployment-continuity test suites (AGENTS.md)

## Stall detection

Stall verdicts are computed only from host-recorded facts, never from what the model claims about its progress. They are deterministic: the same working-memory state and turn always produce the same verdict. They are computed in `WorkingMemory.fold` and stored in the v2 conversation state, so replay and resume see the same values.

### Progress state

`WorkingMemory.State` gains a `progress` record:

| Field | Content |
|---|---|
| `checkFingerprints` | Fingerprints of the last 3 `build`/`run_tests` results: tool, outcome, and the sorted error entries with line and column numbers and absolute path prefixes removed |
| `writesSinceCheck` | Whether at least one write happened since each recorded check |
| `writeHistory` | For each path, the content hashes it has had (capped at 16 per path), plus a count of consecutive non-progress writes |
| `lastNewFactTurn` | The last turn that produced a new fact (defined below) |
| `nudged` | Signal kinds already nudged since the last resume |
| `stall` | The current verdict: `null` or `{signal, sinceTurn, evidence}` |

A turn produces a **new fact** when any of these happens:
- a `write_file` returns `changed:true` with a content hash that path has not had before;
- a check produces a fingerprint different from the previous check;
- a `read_file` or `list_files` covers a path or listing not seen before.

### Signals

| Signal | Rule (thresholds are frozen with the run's limits) |
|---|---|
| `REPEATED_FAILURE` | The last 3 check results have the same failing fingerprint, with at least one write between each pair (default 3) |
| `REPEATED_CONTENT` | 3 consecutive writes, across any paths, each with `changed:false` or returning its path to a content hash it already had (default 3) |
| `NO_NEW_FACTS` | 6 consecutive turns without a new fact (default 6) |
| `NO_TOOL_CALL` | A second reply without a tool call, after the existing protocol reminder |

### Ladder

1. **Nudge.** The first time a signal fires after the last resume (or since the start), `fold` appends a host-written user message. It states the signal and its evidence and asks for a different approach. Example for `REPEATED_FAILURE`: "The last 3 checks failed with the same errors: <errors, capped as in errorLines>. The writes between them did not change these errors. Change the specific lines named, or take a different approach." `NO_TOOL_CALL` keeps its existing reminder as its nudge. The run continues.
2. **Pause.** If a signal that was already nudged fires again, the verdict is a stall, and the workflow pauses with reason `STALLED`.

After a pause, the signal's counters restart. A resume with a hint also clears `nudged`, so the agent gets one fresh nudge before the next stall pause.

### Workflow contract

`RunActivities.appendConversation` returns `ConversationStep(String blobId, StallVerdict stall)` instead of a bare blob ID. `StallVerdict` is `null` or `{signal, sinceTurn, evidence}`, where evidence is bounded: at most 5 error entries and 8 write receipts with turns. The workflow branches on it after each append.

## Pause at limit

### Reasons

The workflow pauses at a turn boundary, never in the middle of a model or tool call. Anything in flight finishes and is recorded first.

| Reason | Trigger (previously terminal) |
|---|---|
| `STALLED` | The ladder above |
| `TURN_LIMIT` | The current allowance of model turns is used up (was `LIMIT_EXHAUSTED`) |
| `TOOL_LIMIT` | The current allowance of tool calls is used up (was `LIMIT_EXHAUSTED`) |
| `TIME_LIMIT` | The active-time allowance is used up (was `EXPIRED`) |

These stay terminal, as today: context overflow, infrastructure failure, cancellation, and a submit outcome.

A limit reached in the middle of a reply needs one rule. If a reply asks for more tool calls than remain, the workflow runs the calls that fit, records the rest as not run with code `BUDGET_PAUSED`, and pauses. On resume, the model sees those results and can repeat the calls.

### Allowances

`Limits` keeps its validated per-run bounds: 40 turns, 100 tool calls, 80 model attempts, and 30 minutes. A resume grants an **extension**: additional turns, tool calls, and active minutes. Model attempts scale with turns at 2 per turn. Ceilings, frozen at start:

| Per resume | Per run, all extensions together |
|---|---|
| ≤ 40 turns, ≤ 100 tool calls, ≤ 30 minutes | ≤ 3 resumes; ≤ 120 extra turns; ≤ 300 extra tool calls; ≤ 90 extra minutes |

Requests above a ceiling are rejected with `400` before delivery. A run that has used its last allowed resume ends with its pause reason when it next pauses.

### Active time

The run deadline becomes an **active-time** budget: paused time does not count. Crash and restart downtime still counts, as today. The workflow tracks `activeMillisUsed` and computes each Activity deadline from the remaining active allowance.

### While paused

- **Status and record.** The projection status is `PAUSED`. The pause record holds the reason, signal, evidence, allowance used, the latest working-memory `Progress:`/`Next:` note, `pausedAt`, and `waitUntil`.
- **Execution slot.** The run releases its execution slot (`releaseAdmission`), so queued work can be admitted. On resume, it requests admission again through the existing FIFO queue and waits until admitted.
- **Waiting limit.** The workflow waits on a durable condition for a resume or stop signal, or until the waiting limit expires (default 7 days, configurable, frozen at start). On expiry the run ends with its pause reason as the terminal outcome.
- **Cancel.** A cancel command while paused ends the run `CANCELLED` and does not wait for admission.

### Operator commands

Resume and stop are durable commands delivered through the existing command path, with command IDs, `expectedVersion` checks, and the same idempotency as cancel.

| Command | Payload | Effect |
|---|---|---|
| `RESUME` | `extraTurns?`, `extraToolCalls?`, `extraMinutes?`, `hint?` (≤ 2,000 characters) | Grants the extension, re-admits, appends the hint as an operator message, clears stall counters, and continues the loop |
| `STOP` | none | Ends the run with its pause reason as the terminal outcome (`STALLED`, `LIMIT_EXHAUSTED`, or `EXPIRED`), keeping the snapshot and evidence |

The working memory records an operator hint as a turn of role `user` whose content starts with "Operator:". The working state lists every hint. A hint never reaches the starter, the runner image, or the evaluator. Model output, tool requests, and hint text remain untrusted inputs to the conversation only.

### Benchmark honesty

Every resume is recorded with its extension, hint text, command ID, and time. A run with any hint is labeled **assisted**; a run with only extensions is labeled **extended**. The trusted evaluator's verdict is unchanged by these labels, but reports, `STATUS.md`, and evidence must state the label. An assisted pass is never reported as an unassisted model completion. The proposed M7 criterion is unassisted, with extensions allowed and reported.

## API, CLI, and UI

### API

The API binds to loopback only, with the existing host and origin checks.

| Route | Behavior |
|---|---|
| `POST /api/runs/{id}/resume`, `POST /api/work/{id}/resume` | Accepts `{commandId, expectedVersion, extraTurns?, extraToolCalls?, extraMinutes?, hint?}`. Returns `202`. Returns `409` if the run is not `PAUSED` or the version is stale, and `400` above a ceiling. |
| `POST /api/runs/{id}/stop`, `POST /api/work/{id}/stop` | Accepts `{commandId, expectedVersion}`. Returns `202`, or `409` if the run is not `PAUSED`. |
| Existing detail routes | The projection gains `pause` (as above), `resumes[]`, `allowance {used, granted, ceilings}`, and `label` (`assisted`, `extended`, or none). |
| Existing event stream | Adds events `NUDGED`, `PAUSED`, `RESUMED`, and `STOPPED`, each with a bounded payload. |

### CLI

- `harness.py resume RUN --command-id X [--turns N] [--tools N] [--minutes N] [--hint TEXT]`
- `harness.py stop RUN --command-id X`

`status` prints the pause block, allowance, and label.

### UI

Built on the existing loop console in `web/`:

- **Notification banner.** Shown on every page while any run or work item is `PAUSED`, with a count and links. It comes from durable projections, survives reloads, and never infers state from the browser connection.
- **Loop diagram.** Gains a **Paused** node, lit from the `PAUSED` event.
- **Pause panel.** Shows:
  - the reason in plain words;
  - signal evidence (repeated errors, write receipts with turns);
  - allowance used and the remaining ceilings;
  - the model's last `Progress:`/`Next:` note;
  - resume fields showing their maximums, a hint box, and **Resume** and **Stop** buttons.
- **Event list.** Shows `NUDGED` entries, so the operator can see the warning that came before a pause.
- **Resume history.** Shows each resume and the run's label.
- **Accessibility.** Text labels for states and keyboard-operable controls, matching the console's current standard.

## Durability and compatibility

- **Versioning.** Workflows use pinned worker-deployment versioning. Runs started on an earlier build complete on that build with the old terminal behavior; the new build is made current for new runs only. The new behavior therefore needs no in-code version branch. An earlier build's workers must stay available until its runs finish, as today.
- **Wait and signals.** Pause is a durable `Workflow.await` on resume or stop signals, with the waiting-limit timer. A command accepted while a worker is down is applied when the worker returns. A repeated command ID returns its original outcome.
- **Storage.** A new migration named for its purpose, for example `V9__record_run_pauses_and_resumes.sql`, stores pause and resume records and adds the projection fields. Existing migrations are not edited.
- **Working memory.** The v2 conversation gains the `progress` state as an optional field. Conversations without it fold as if all counters were empty, so v2 blobs written before M1 stay readable.
- **Replay.** Stall state lives in the working-memory blob and pause history in the workflow and its records. Replay and resume see the same values.

## Testing

Under the owner's minimal-test-growth rule, extend existing tests first and report the net count of new test methods.

| Test | Change | Expected net new methods |
|---|---|---|
| `WorkingMemoryTest.longSessionStaysWithinBudgetAndCarriesTheHandOff` | Add turns producing a repeated-failure nudge then stall, an identical-rewrite nudge then stall, and a no-new-facts window; assert verdicts, determinism, and that pre-M1 v2 blobs fold | 0 |
| `CodingWorkflowTest` | One scripted run: turn limit → `PAUSED` → resume with extra turns and hint → submit; within the same method, a second run paused then stopped | 1 |
| `RunApiIT` / `WorkApiIT` | Extend existing flows: resume/stop routes, `409` when not paused, `400` above a ceiling, command-ID idempotency | 0–1 |
| UI | No automated browser suite exists. Verify the banner → detail → resume path manually against a scripted run and record it in evidence | 0 |

## Acceptance

Each item is recorded with its command, date, and commit:

1. `./gradlew build` passes.
2. A live `qwen3.5:9b` benchmark run pauses with `STALLED` or a limit reason and shows its evidence in the UI. The operator resumes it once from the UI with an extension and no hint. It then submits or pauses again. The run is reported as **extended**, and any evaluator verdict is recorded as observed.
3. A scripted repository work item pauses, resumes, and reaches "Ready for review".

## Risks

- **False stalls.** A legitimate slow approach could trip `NO_NEW_FACTS`. Mitigation: one nudge before any pause; thresholds frozen per run and visible; a pause costs the operator a resume, not the work.
- **Gaming the fingerprint.** A model could make cosmetic changes to dodge `REPEATED_FAILURE`. Repeated identical errors still trip it. `REPEATED_CONTENT` covers exact repeats. Semantic stalls beyond that belong to M5's QA.
- **Paused runs piling up.** Each paused run frees its slot, and the waiting limit eventually ends abandoned runs.
