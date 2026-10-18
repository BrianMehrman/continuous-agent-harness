# Stall Detection and Pause-at-Limit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Coding runs detect no-progress loops from recorded facts and pause at their limits. The operator can resume a paused run with extra budget or a hint, or stop it, from the API, the CLI, and the console.

**Architecture:**
- `WorkingMemory.fold` computes stall signals from host facts and stores them in the v2 conversation state.
- `appendConversation` returns those verdicts to `CodingWorkflowImpl`.
- The workflow replaces its terminal limit exits with a durable pause: it releases admission, publishes `PAUSED`, and waits for a `resume` or `stop` update or the waiting limit.
- Resume and stop travel through the existing durable command outbox, like cancel.
- Pause history lives in the run event ledger. The API, CLI, and UI read it from there.

**Tech Stack:** Java 21, Spring Boot, Temporal Java SDK (`@UpdateMethod`, `Workflow.await`), PostgreSQL with Flyway, Jackson 3 (`tools.jackson`), JUnit 5, Python 3 CLI, React + Vite console in `web/`.

**Spec:** `docs/superpowers/specs/2026-10-17-stall-detection-and-pause-design.md`

## Global Constraints

- Thresholds: `REPEATED_FAILURE` 3, `REPEATED_CONTENT` 3, `NO_NEW_FACTS` 6, frozen at start.
- Per-resume ceilings: ≤ 40 turns, ≤ 100 tool calls, ≤ 30 minutes.
- Per-run extension ceilings: ≤ 3 resumes, ≤ 120 extra turns, ≤ 300 extra tool calls, ≤ 90 extra minutes.
- Waiting limit: 7 days by default, configurable, frozen at start.
- Hints: ≤ 2,000 characters. A hint reaches only the conversation, never the starter, the runner, or the evaluator.
- `Limits` keeps its validated per-run bounds (40 turns, 100 tool calls, 80 model attempts, 30 minutes). Extensions are separate allowances.
- Model attempts scale with turns: 2 per extra turn.
- Paused time does not count against active time. Crash downtime still does.
- Pause reasons map to terminal outcomes on stop or wait expiry: `STALLED` → `STALLED`, `TURN_LIMIT` and `TOOL_LIMIT` → `LIMIT_EXHAUSTED`, `TIME_LIMIT` → `EXPIRED`.
- Labels: a run with any hint is `assisted`; a run with only extensions is `extended`.
- Pinned worker-deployment versioning stays. No in-code workflow version branch.
- Name the new migration for its purpose. Never edit an existing migration.
- Minimal test growth: extend existing tests first and report the net count of new test methods. No crash, race, or deployment-continuity tests.
- Never print or commit secrets, `.secrets/`, or machine-specific model profiles.

## Plan rulings (deviations from the spec's wording)

- **Pause and resume records live in the event ledger.**
  - Spec: "a new migration … stores pause and resume records and adds the projection fields."
  - Ruling: `PAUSED`, `RESUMED`, `NUDGED`, and `STOPPED` run events carry the records in their payload blobs, which are durable, ordered, and already rebuildable. Projection fields are derived from them at query time.
  - The only migration widens `run_command.kind` to allow `RESUME` and `STOP`.
  - Cost if wrong: a later table can be backfilled from events.
- **Active time is checked before each dispatch.**
  - Spec: the time limit pauses at a turn boundary.
  - Ruling: the workflow checks active time before each model call and each tool call. An activity already running keeps its own cap (model ≤ 180 s, build/test ≤ 120 s, submit ≤ 180 s) and is not cut short by the run's time allowance. The old run-deadline timer scope is removed.
  - Overrun is bounded by one activity cap.
  - Cost if wrong: a run can exceed its active time by up to 180 s.
- **Model attempts never run out mid-turn.**
  - Ruling: a turn starts only if `dispatched + perTurnModelAttempts ≤ attemptsGranted`, so attempts can't be exhausted partway through a turn. If attempts run out, the run pauses with reason `TURN_LIMIT`.
- **Pause policy is an optional field on `RunSpec`.**
  - Ruling: `RunSpec` gains a nullable `PausePolicy pausePolicy` component, plus a 10-argument constructor that passes `null`. Stored specs without it deserialize with `null`, which means `PausePolicy.defaults()`.
- **The workflow execution timeout grows to 40 days.**
  - Ruling: `TemporalWorkflowGateway` sets it to 40 days so that up to 4 pauses of 7 days each fit.
- **Per-run extension totals are enforced twice.**
  - The API checks totals before acceptance, from the run's resume commands that were accepted or applied. It returns `400` if a total would be exceeded.
  - The workflow re-checks them when applying, and returns `REJECTED_LIMIT`.

## Review Focus

1. **A resume issued while the run is not paused.** Examples: running, queued for re-admission, or terminal. It must be rejected with `409` (`REJECTED_STATE` / `REJECTED_TERMINAL`) and never applied later. Covered by the Task 5 API assertions and Task 3's `control` guard.
2. **A cancel while paused, or while waiting for re-admission after a resume.** It must end the run `CANCELLED` promptly, without waiting for the slot. Task 3 adds an assertion: cancel from paused ends `CANCELLED`.
3. **A reply whose tool calls exceed the remaining allowance.** The calls that fit run; the rest return `BUDGET_PAUSED` and are visible to the model after resume. The run never runs a call past the allowance. Task 3's resume test uses a two-call reply at the boundary.
4. **v2 conversations stored before M1** (no `progress` field). They must still fold and render, with stall detection starting from empty counters. Task 1 asserts this.
5. **A stalled run whose paused projection a UI reload reads.** The banner and pause panel must come from durable events, not page state, and show the reason and evidence after a reload. Task 7's manual check reloads the page while the run is paused.

---

## File map

| File | Responsibility | Task |
|---|---|---|
| `src/main/java/com/brianmehrman/harness/execution/WorkingMemory.java` | Progress state, stall signals, nudge text, `resume` | 1 |
| `src/main/java/com/brianmehrman/harness/execution/PausePolicy.java` (new) | Frozen thresholds, waiting limit, and ceilings | 2 |
| `src/main/java/com/brianmehrman/harness/execution/ConversationStep.java` (new) | Return value of `appendConversation` | 2 |
| `src/main/java/com/brianmehrman/harness/execution/RunSpec.java` | Optional `pausePolicy` | 2 |
| `src/main/java/com/brianmehrman/harness/execution/RunActivities.java`, `RunActivitiesImpl.java` | `appendConversation` returns step; `resumeConversation`; `publishEvent` | 2 |
| `src/main/java/com/brianmehrman/harness/execution/RunStatus.java` (new) | One terminal-status set | 3 |
| `src/main/java/com/brianmehrman/harness/execution/ResumeCommand.java`, `StopCommand.java` (new) | Validated operator commands | 3 |
| `src/main/java/com/brianmehrman/harness/execution/CodingWorkflow.java`, `CodingWorkflowImpl.java` | Allowances, pause, resume, and stop | 3 |
| `src/main/resources/db/migration/V9__allow_resume_and_stop_commands.sql` (new) | Widen command kinds | 4 |
| `src/main/java/com/brianmehrman/harness/runs/RunCommandService.java`, `CommandDispatcher.java`, `WorkflowGateway.java`, `TemporalWorkflowGateway.java` | Durable delivery of resume and stop | 4 |
| `src/main/java/com/brianmehrman/harness/execution/RunAdmissionStore.java` | Resumed runs rejoin FIFO by resume time | 4 |
| `src/main/java/com/brianmehrman/harness/runs/PauseHistory.java` (new) | Read pause, resumes, and label from events | 5 |
| `src/main/java/com/brianmehrman/harness/runs/RunQueryService.java`, `RepositoryWorkService.java` | Expose pause data; paused lists | 5 |
| `src/main/java/com/brianmehrman/harness/api/RunController.java`, `WorkController.java` | Resume and stop routes | 5 |
| `scripts/harness.py` | `resume`, `stop` | 6 |
| `web/src/api.ts`, `web/src/App.tsx`, `web/src/styles.css` | Banner, Paused node, pause panel | 7 |
| `docs/api.md`, `docs/development.md`, `docs/STATUS.md`, `docs/evidence/local-task-tracker.md` | Documentation and evidence | 8 |

---

### Task 1: Stall detection in the working memory

**Files:**
- Modify: `src/main/java/com/brianmehrman/harness/execution/WorkingMemory.java`
- Test: `src/test/java/com/brianmehrman/harness/execution/WorkingMemoryTest.java` (extend `longSessionStaysWithinBudgetAndCarriesTheHandOff`)

**Interfaces:**
- Produces:
  - `WorkingMemory.Thresholds(int repeatedFailure, int repeatedContent, int noNewFacts)` with `static Thresholds defaults()` → (3, 3, 6).
  - `WorkingMemory.StallVerdict(String signal, int sinceTurn, List<String> evidence)`.
  - `WorkingMemory.Progress(Thresholds thresholds, List<String> checks, boolean writeSinceCheck, Map<String, List<String>> writeHistory, int nonProgressWrites, List<String> recentWrites, int lastNewFactTurn, List<String> seen, List<String> nudged, List<String> hints, String fired, StallVerdict stall)`.
  - `State` gains a sixth component, `Progress progress` (nullable for blobs written before M1).
  - `static Conversation start(String runId, String system, String task, int contextTokens, int outputTokens, Thresholds thresholds)`; the existing 5-argument `start` delegates with `Thresholds.defaults()`.
  - `static Conversation resume(Conversation memory, String hint)`.
  - `static String nudgeSignal(Conversation memory)`: returns `progress.fired` when this fold nudged, otherwise `null`.
  - `static StallVerdict stall(Conversation memory)`: returns `progress.stall`, or `null`.

- [ ] **Step 1: Extend the long-session test (RED)**

In `WorkingMemoryTest`, keep the existing 20-turn loop unchanged. After the existing final assertions of `longSessionStaysWithinBudgetAndCarriesTheHandOff`, append this block. Add the helper methods to the test class.

```java
        // M1: stall signals. Each phase starts from a resume, which restarts counters and clears nudges after a hint.
        memory = WorkingMemory.resume(memory, "Operator: start the stall phases");
        assertTrue(lastMessage(memory).get("content").toString().startsWith("Operator:"));
        assertTrue(memory.state().progress().hints().contains("Operator: start the stall phases"));

        // REPEATED_FAILURE: identical failing builds with writes between them, nudge on the 3rd, stall on the 4th.
        String failing = "/work/input/src/Main.java:12: error: variable dataDir might not have been initialized";
        String movedLine = "/work/input/src/Main.java:48: error: variable dataDir might not have been initialized";
        for (int i = 0; i < 4; i++) {
            memory = fold(memory, "write_file", write("Main.java", "absent", "attempt " + i),
                    Map.of("status", "OK", "path", "Main.java", "sha256", SnapshotHasher.content("attempt " + i), "changed", true));
            memory = fold(memory, "build", "{}", Map.of("status", "FAILED", "exitCode", 1,
                    "errors", List.of(i % 2 == 0 ? failing : movedLine), "logTail", "..."));
            if (i < 2) assertNull(WorkingMemory.nudgeSignal(memory));
            if (i == 2) {
                assertEquals("REPEATED_FAILURE", WorkingMemory.nudgeSignal(memory), "Line numbers must not hide a repeat");
                assertNull(WorkingMemory.stall(memory));
                assertTrue(lastMessage(memory).get("content").toString().contains("The last 3 checks failed with the same errors"));
            }
            if (i == 3) assertEquals("REPEATED_FAILURE", WorkingMemory.stall(memory).signal());
        }
        assertTrue(WorkingMemory.stall(memory).evidence().stream().anyMatch(e -> e.contains("dataDir")));

        // REPEATED_CONTENT: identical rewrites, nudge on the 3rd, stall on the 4th.
        memory = WorkingMemory.resume(memory, null);
        String same = SnapshotHasher.content("attempt 3");
        for (int i = 0; i < 4; i++) {
            memory = fold(memory, "write_file", write("Main.java", same, "attempt 3"),
                    Map.of("status", "OK", "path", "Main.java", "sha256", same, "changed", false));
            if (i == 2) assertEquals("REPEATED_CONTENT", WorkingMemory.nudgeSignal(memory));
            if (i == 3) assertEquals("REPEATED_CONTENT", WorkingMemory.stall(memory).signal());
        }

        // NO_NEW_FACTS: the first read of Main.java is new; six rereads later the agent is nudged, then stalled.
        memory = WorkingMemory.resume(memory, "Operator: read less, change more");
        for (int i = 0; i < 8; i++) {
            memory = fold(memory, "read_file", "{\"path\":\"Main.java\"}",
                    Map.of("status", "OK", "content", "attempt 3", "sha256", same));
            if (i < 6) assertNull(WorkingMemory.nudgeSignal(memory));
            if (i == 6) assertEquals("NO_NEW_FACTS", WorkingMemory.nudgeSignal(memory));
            if (i == 7) assertEquals("NO_NEW_FACTS", WorkingMemory.stall(memory).signal());
        }

        // A v2 blob written before M1 has no progress field and still folds.
        var tree = (tools.jackson.databind.node.ObjectNode) JSON.readTree(WorkingMemory.write(memory));
        ((tools.jackson.databind.node.ObjectNode) tree.get("state")).remove("progress");
        var old = WorkingMemory.read(JSON.writeValueAsBytes(tree));
        assertNull(old.state().progress());
        var folded = fold(old, "list_files", "{}", Map.of("status", "OK", "files", List.of("Main.java")));
        assertNotNull(folded.state().progress());
        assertTrue(WorkingMemory.fits(folded));
```

Add these to the test class, reusing its existing `write(path, expected, content)` and `lastMessage(...)` helpers and its `outcome(...)` helper:

```java
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    private static WorkingMemory.Conversation fold(WorkingMemory.Conversation memory, String tool, String args,
            Map<String, Object> result) {
        String id = "run-1:turn-" + (memory.state().turn() + 1) + ":tool-1";
        var reply = new ModelReply("Progress: stall phase\nNext: keep going",
                List.of(new ToolRequest("p", tool, args)), 1L, 1L, "stop");
        var next = WorkingMemory.fold(memory, reply, List.of(outcome(id, tool, result)));
        assertArrayEquals(WorkingMemory.write(next), WorkingMemory.write(WorkingMemory.fold(memory, reply,
                List.of(outcome(id, tool, result)))), "Stall verdicts are deterministic");
        assertTrue(WorkingMemory.fits(next));
        return next;
    }
```

If the class already declares a `JSON` constant or a helper with the same name, reuse it instead of adding a duplicate.

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew test --tests 'com.brianmehrman.harness.execution.WorkingMemoryTest'`
Expected: compilation FAIL, because `WorkingMemory.resume`, `nudgeSignal`, `stall`, and `State.progress()` do not exist yet.

- [ ] **Step 3: Add progress records and `start`/`resume`**

In `WorkingMemory.java`:

```java
    public record State(int turn, List<FileState> files, CheckState lastCheck, List<Rejection> rejected, Note note,
            Progress progress) {}
    public record Thresholds(int repeatedFailure, int repeatedContent, int noNewFacts) {
        public static Thresholds defaults() { return new Thresholds(3, 3, 6); }
    }
    public record StallVerdict(String signal, int sinceTurn, List<String> evidence) {}
    public record Progress(Thresholds thresholds, List<String> checks, boolean writeSinceCheck,
            Map<String, List<String>> writeHistory, int nonProgressWrites, List<String> recentWrites,
            int lastNewFactTurn, List<String> seen, List<String> nudged, List<String> hints,
            String fired, StallVerdict stall) {
        static Progress empty(Thresholds thresholds, int turn) {
            return new Progress(thresholds, List.of(), false, Map.of(), 0, List.of(), turn,
                    List.of(), List.of(), List.of(), null, null);
        }
    }
    static final int HISTORY_PER_PATH = 16;
    static final int SEEN_KEPT = 128;
    static final int WRITES_KEPT = 8;
    static final int EVIDENCE_ERRORS = 5;
    private static final Pattern POSITION = Pattern.compile(":\\d+(:\\d+)?(?=:)");
```

Replace `start` with:

```java
    public static Conversation start(String runId, String system, String task, int contextTokens, int outputTokens) {
        return start(runId, system, task, contextTokens, outputTokens, Thresholds.defaults());
    }

    public static Conversation start(String runId, String system, String task, int contextTokens, int outputTokens,
            Thresholds thresholds) {
        int max = (int) ((contextTokens - outputTokens) * 0.9);
        return new Conversation(2, runId, new Budget(contextTokens, outputTokens, max), system, task,
                new State(0, List.of(), null, List.of(), null, Progress.empty(thresholds, 0)), List.of(),
                new Compacted(0, List.of()));
    }

    /** Restart stall counters after an operator resume; a hint also clears nudges and enters the conversation. */
    public static Conversation resume(Conversation memory, String hint) {
        State state = memory.state();
        Progress old = state.progress() == null ? Progress.empty(Thresholds.defaults(), state.turn()) : state.progress();
        var hints = new ArrayList<>(old.hints());
        if (hint != null) hints.add(hint.startsWith("Operator:") ? hint : "Operator: " + hint);
        Progress restarted = new Progress(old.thresholds(), List.of(), false, old.writeHistory(), 0, List.of(),
                state.turn(), old.seen(), hint == null ? old.nudged() : List.of(), List.copyOf(hints), null, null);
        State next = new State(state.turn(), state.files(), state.lastCheck(), state.rejected(), state.note(), restarted);
        var turns = new ArrayList<>(memory.recent());
        if (hint != null) turns.add(new Turn(state.turn(), List.of(message("user", hints.getLast()))));
        Conversation resumed = keep(memory, next, turns, RECENT_TURNS + (hint == null ? 0 : 1));
        return fits(resumed) ? resumed : keep(memory, next, turns, 1);
    }

    public static String nudgeSignal(Conversation memory) {
        return memory.state().progress() == null ? null : memory.state().progress().fired();
    }

    public static StallVerdict stall(Conversation memory) {
        return memory.state().progress() == null ? null : memory.state().progress().stall();
    }
```

- [ ] **Step 4: Compute progress and signals in `update`, and add the nudge message**

In `update(...)`, compute the existing values as now. Then, before `return`, replace the final return with:

```java
        Note note = note(turn, reply.text());
        Progress progress = progress(previous.progress() == null
                ? Progress.empty(Thresholds.defaults(), previous.turn()) : previous.progress(), turn, reply, outcomes);
        return new State(turn, List.copyOf(files.values()), check, List.copyOf(rejected),
                note == null ? previous.note() : note, progress);
```

Add these methods:

```java
    private static Progress progress(Progress p, int turn, ModelReply reply, List<ToolOutcome> outcomes) {
        var checks = new ArrayList<>(p.checks());
        boolean writeSinceCheck = p.writeSinceCheck();
        var history = new TreeMap<String, List<String>>();
        p.writeHistory().forEach((path, hashes) -> history.put(path, new ArrayList<>(hashes)));
        int nonProgress = p.nonProgressWrites();
        var writes = new ArrayList<>(p.recentWrites());
        var seen = new LinkedHashSet<>(p.seen());
        boolean newFact = false;
        for (int i = 0; i < outcomes.size() && i < reply.tools().size(); i++) {
            ToolOutcome outcome = outcomes.get(i);
            JsonNode result = parse(outcome.responseJson());
            if (result == null || "ERROR".equals(result.path("status").asText(""))) continue;
            switch (outcome.name()) {
                case "write_file" -> {
                    String path = text(result, "path");
                    String sha = result.path("sha256").asText("");
                    if (path == null) continue;
                    writeSinceCheck = true;
                    var hashes = history.computeIfAbsent(path, k -> new ArrayList<>());
                    boolean repeat = result.path("changed").isBoolean() && !result.path("changed").asBoolean()
                            || hashes.contains(sha);
                    nonProgress = repeat ? nonProgress + 1 : 0;
                    if (!repeat) { newFact = true; hashes.add(sha); }
                    while (hashes.size() > HISTORY_PER_PATH) hashes.removeFirst();
                    writes.add("turn " + turn + " write " + path + " sha256=" + cap(sha, 12) +
                            (repeat ? " unchanged" : " changed"));
                    while (writes.size() > WRITES_KEPT) writes.removeFirst();
                }
                case "build", "run_tests" -> {
                    String fingerprint = fingerprint(outcome.name(), result);
                    String last = checks.isEmpty() ? null : checks.getLast().substring(checks.getLast().indexOf('|') + 1);
                    if (!fingerprint.equals(last)) newFact = true;
                    checks.add((writeSinceCheck ? "w" : "-") + turn + "|" + fingerprint);
                    while (checks.size() > p.thresholds().repeatedFailure()) checks.removeFirst();
                    writeSinceCheck = false;
                }
                case "read_file", "list_files" -> {
                    JsonNode args = parse(reply.tools().get(i).argumentsJson());
                    String key = outcome.name() + ":" + (args == null ? "" : args.path("path").asText(""));
                    if (seen.add(key)) newFact = true;
                }
                default -> newFact = true;
            }
        }
        var seenList = new ArrayList<>(seen);
        while (seenList.size() > SEEN_KEPT) seenList.removeFirst();
        int lastNewFact = newFact ? turn : p.lastNewFactTurn();
        String signal = null;
        var evidence = new ArrayList<String>();
        if (reply.tools().isEmpty()) {
            signal = "NO_TOOL_CALL";
            evidence.add("turn " + turn + " reply had no tool call");
        } else if (checks.size() == p.thresholds().repeatedFailure() && failingRepeat(checks)) {
            signal = "REPEATED_FAILURE";
            for (String c : checks) evidence.add("check at turn " + c.substring(1, c.indexOf('|')));
            parse(outcomes.getLast().responseJson()).path("errors").forEach(e -> {
                if (evidence.size() < EVIDENCE_ERRORS + checks.size()) evidence.add(e.asText(""));
            });
        } else if (nonProgress >= p.thresholds().repeatedContent()) {
            signal = "REPEATED_CONTENT";
            evidence.addAll(writes);
        } else if (turn - lastNewFact >= p.thresholds().noNewFacts()) {
            signal = "NO_NEW_FACTS";
            evidence.add("no changed write, new check result, or new read since turn " + lastNewFact);
        }
        var nudged = new ArrayList<>(p.nudged());
        String fired = null;
        StallVerdict stall = null;
        if (signal != null) {
            if (nudged.contains(signal)) stall = new StallVerdict(signal, turn, List.copyOf(evidence));
            else { nudged.add(signal); fired = signal; }
        }
        return new Progress(p.thresholds(), List.copyOf(checks), writeSinceCheck, Map.copyOf(history), nonProgress,
                List.copyOf(writes), lastNewFact, List.copyOf(seenList), List.copyOf(nudged), p.hints(), fired, stall);
    }

    private static boolean failingRepeat(List<String> checks) {
        String first = checks.getFirst().substring(checks.getFirst().indexOf('|') + 1);
        if (!first.contains("|FAILED|")) return false;
        for (int i = 0; i < checks.size(); i++) {
            String c = checks.get(i);
            if (!c.substring(c.indexOf('|') + 1).equals(first)) return false;
            if (i > 0 && c.charAt(0) != 'w') return false;
        }
        return true;
    }

    private static String fingerprint(String tool, JsonNode result) {
        var errors = new ArrayList<String>();
        result.path("errors").forEach(e -> errors.add(POSITION.matcher(e.asText("").replace("/work/input/", ""))
                .replaceAll("")));
        java.util.Collections.sort(errors);
        return tool + "|" + result.path("status").asText("") + "|" + String.join("\n", errors);
    }

    private static String nudge(String signal, Progress progress, List<String> errors) {
        return switch (signal) {
            case "REPEATED_FAILURE" -> "The last " + progress.thresholds().repeatedFailure() +
                    " checks failed with the same errors:\n" + String.join("\n", errors) +
                    "\nThe writes between them did not change these errors. Change the specific lines named, or take a different approach.";
            case "REPEATED_CONTENT" -> "Your last " + progress.thresholds().repeatedContent() +
                    " writes did not change any file (changed=false, or a previous version restored). " +
                    "Rewriting the same content will not fix the reported errors. Edit the specific lines that cause them, or take a different approach.";
            case "NO_NEW_FACTS" -> "Your last " + progress.thresholds().noNewFacts() +
                    " turns produced no new information: no changed file, no new check result, no new file read. " +
                    "Take a different step, such as running build or editing the code that fails.";
            default -> null; // NO_TOOL_CALL uses the existing protocol reminder
        };
    }
```

Change `messages(reply, outcomes, state)` so that, after the existing reminder line, it appends the nudge:

```java
        if (outcomes.isEmpty()) messages.add(message("user", reminder(state, reply)));
        Progress progress = state.progress();
        if (progress != null && progress.fired() != null) {
            String text = nudge(progress.fired(), progress,
                    state.lastCheck() == null ? List.of() : state.lastCheck().errors().stream().limit(EVIDENCE_ERRORS).toList());
            if (text != null) messages.add(message("user", text));
        }
        return messages;
```

In `stateText(...)`, before the note block, list hints:

```java
        if (state.progress() != null && !state.progress().hints().isEmpty()) {
            text.append("\nOperator hints:");
            state.progress().hints().forEach(hint -> text.append("\n- ").append(hint));
        }
```

`keep(...)` and `mapRecent(...)` already copy `state` whole. In `keep(...)`, an operator-hint turn shares its turn number with the model turn before it, so add a compacted turn's `runId:turn-N` reference only if `attempts` does not already contain it:

```java
            String ref = base.runId() + ":turn-" + kept.removeFirst().turn();
            if (!attempts.contains(ref)) attempts.add(ref);
            folded++;
```

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `./gradlew test --tests 'com.brianmehrman.harness.execution.WorkingMemoryTest'`
Expected: PASS (2 tests). Then run `./gradlew test`. Expected: PASS. `ToolRouterTest` and `RepositoryTaskIT` don't construct `State` directly. If any other test does, add `null` as the sixth argument.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/brianmehrman/harness/execution/WorkingMemory.java src/test/java/com/brianmehrman/harness/execution/WorkingMemoryTest.java
git commit -m "feat: detect stalled coding runs from recorded facts"
```

---

### Task 2: Activity contract: pause policy, conversation step, events

**Files:**
- Create: `src/main/java/com/brianmehrman/harness/execution/PausePolicy.java`
- Create: `src/main/java/com/brianmehrman/harness/execution/ConversationStep.java`
- Modify: `src/main/java/com/brianmehrman/harness/execution/RunSpec.java`
- Modify: `src/main/java/com/brianmehrman/harness/execution/RunActivities.java`
- Modify: `src/main/java/com/brianmehrman/harness/execution/RunActivitiesImpl.java`
- Modify (fixtures only, signature): `src/test/java/com/brianmehrman/harness/execution/CodingWorkflowTest.java`, `CodingWorkflowSmokeIT.java`, `src/test/java/com/brianmehrman/harness/api/ApiWorkflowIT.java`
- Modify: `src/main/java/com/brianmehrman/harness/execution/CodingWorkflowImpl.java` (call sites only)
- Test: `src/test/java/com/brianmehrman/harness/execution/RepositoryTaskIT.java` (extend)

**Interfaces:**
- Consumes: `WorkingMemory.Thresholds`, `resume`, `nudgeSignal`, and `stall` from Task 1.
- Produces:
  - `PausePolicy(int repeatedFailure, int repeatedContent, int noNewFacts, long waitMillis, int maxResumes, int resumeTurns, int resumeToolCalls, int resumeMinutes, int totalExtraTurns, int totalExtraToolCalls, int totalExtraMinutes)` with `static PausePolicy defaults()` and `WorkingMemory.Thresholds thresholds()`.
  - `ConversationStep(String blobId, String nudged, WorkingMemory.StallVerdict stall, String note)`. `note` is the latest working-memory note rendered as `Progress: …\nNext: …`, or `null`.
  - `RunSpec(..., Limits limits, PausePolicy pausePolicy)` and a 10-argument constructor.
  - `RunActivities.appendConversation(...)` returns `ConversationStep`.
  - `RunActivities.resumeConversation(String blobId, String hint)` returns `String`.
  - `RunActivities.publishEvent(String runId, long sequence, long version, String status, String snapshotSha256, String type, Map<String, Object> detail)`, as a default no-op in the interface.

- [ ] **Step 1: Extend `RepositoryTaskIT` (RED)**

In `RepositoryTaskIT`, next to the existing working-memory assertions (`assertEquals(3, lastSeen.state().turn());`), add:

```java
            assertNotNull(lastSeen.state().progress(), "M1 runs carry stall progress");
            assertEquals(WorkingMemory.Thresholds.defaults(), lastSeen.state().progress().thresholds(),
                    "Thresholds come from the frozen pause policy");
```

- [ ] **Step 2: Run and confirm it fails**

Run, with services up as in `docs/development.md`: `./gradlew integrationTest --tests 'com.brianmehrman.harness.execution.RepositoryTaskIT'`
Expected: FAIL. Before Task 1 this is a compilation failure; after Task 1 it passes only once `startConversation` passes thresholds. If it already passes because Task 1's 5-argument `start` uses defaults, record "RED not observable: defaults equal policy defaults" in the ledger and continue. The compile-level contract in Step 3 is what this task changes.

- [ ] **Step 3: Add the records and the `RunSpec` field**

`PausePolicy.java`:

```java
package com.brianmehrman.harness.execution;

/** Frozen stall thresholds, pause waiting limit, and extension ceilings. */
public record PausePolicy(int repeatedFailure, int repeatedContent, int noNewFacts, long waitMillis,
        int maxResumes, int resumeTurns, int resumeToolCalls, int resumeMinutes,
        int totalExtraTurns, int totalExtraToolCalls, int totalExtraMinutes) {
    public PausePolicy {
        if (repeatedFailure < 2 || repeatedContent < 2 || noNewFacts < 2 || waitMillis < 1 ||
                waitMillis > 7L * 24 * 3_600_000 || maxResumes < 0 || maxResumes > 3 ||
                resumeTurns < 0 || resumeTurns > 40 || resumeToolCalls < 0 || resumeToolCalls > 100 ||
                resumeMinutes < 0 || resumeMinutes > 30 || totalExtraTurns < 0 || totalExtraTurns > 120 ||
                totalExtraToolCalls < 0 || totalExtraToolCalls > 300 || totalExtraMinutes < 0 || totalExtraMinutes > 90)
            throw new IllegalArgumentException("Pause policy exceeds the local coding profile");
    }

    public static PausePolicy defaults() {
        return new PausePolicy(3, 3, 6, 7L * 24 * 3_600_000, 3, 40, 100, 30, 120, 300, 90);
    }

    public WorkingMemory.Thresholds thresholds() {
        return new WorkingMemory.Thresholds(repeatedFailure, repeatedContent, noNewFacts);
    }
}
```

`ConversationStep.java`:

```java
package com.brianmehrman.harness.execution;

/** A folded conversation plus the stall facts the workflow acts on. */
public record ConversationStep(String blobId, String nudged, WorkingMemory.StallVerdict stall, String note) {}
```

`RunSpec.java`:

```java
public record RunSpec(String runId, String commandId, String inputSha256, String benchmarkVersion,
        String definitionSha256, String evaluatorVersion, String profileRevision,
        String modelDigest, String seedSnapshotSha256, Limits limits, PausePolicy pausePolicy) {
    public RunSpec(String runId, String commandId, String inputSha256, String benchmarkVersion,
            String definitionSha256, String evaluatorVersion, String profileRevision,
            String modelDigest, String seedSnapshotSha256, Limits limits) {
        this(runId, commandId, inputSha256, benchmarkVersion, definitionSha256, evaluatorVersion,
                profileRevision, modelDigest, seedSnapshotSha256, limits, null);
    }

    public PausePolicy policy() { return pausePolicy == null ? PausePolicy.defaults() : pausePolicy; }
}
```

In `RunCommandService.start` and `RepositoryWorkService.create`, construct `RunSpec` with `PausePolicy.defaults()` as the last argument, so new runs freeze the policy.

- [ ] **Step 4: Change the activity interface and implementation**

`RunActivities.java`: replace the `appendConversation` line and add:

```java
    ConversationStep appendConversation(String previousBlobId, ModelReply reply, List<ToolOutcome> outcomes);
    String resumeConversation(String blobId, String hint);
    default void publishEvent(String runId, long sequence, long version, String status,
            String snapshotSha256, String type, java.util.Map<String, Object> detail) {}
```

`RunActivitiesImpl.java`:
- In `startConversation`, use `WorkingMemory.start(spec.runId(), system, task, profile.contextTokens(), profile.outputTokens(), spec.policy().thresholds())`.
- Replace `appendConversation`:

```java
    @Override public ConversationStep appendConversation(String previousBlobId, ModelReply reply,
            List<ToolOutcome> outcomes) {
        byte[] previous = blobs.get(previousBlobId);
        if (WorkingMemory.isV2(previous)) {
            var next = WorkingMemory.fold(WorkingMemory.read(previous), reply, outcomes);
            var note = next.state().note();
            return new ConversationStep(storeConversation(WorkingMemory.write(next)),
                    WorkingMemory.nudgeSignal(next), WorkingMemory.stall(next),
                    note == null ? null : "Progress: " + note.progress() + "\nNext: " + note.next());
        }
        return new ConversationStep(appendV1(previous, reply, outcomes), null, null, null);
    }

    @Override public String resumeConversation(String blobId, String hint) {
        byte[] previous = blobs.get(blobId);
        if (!WorkingMemory.isV2(previous)) return blobId;
        return storeConversation(WorkingMemory.write(WorkingMemory.resume(WorkingMemory.read(previous), hint)));
    }

    @Override public void publishEvent(String runId, long sequence, long version, String status,
            String snapshotSha256, String type, Map<String, Object> detail) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("version", version);
        payload.put("status", status);
        payload.put("snapshotSha256", snapshotSha256);
        payload.putAll(detail);
        String blob = blobs.put("application/vnd.harness.run-event+json", json.writeValueAsBytes(payload));
        projections.append(new RunEvent(runId, sequence, runId + ":event-" + sequence, type, blob));
    }
```

Move the existing v1 body of `appendConversation` (from `Map<String, Object> root = …` to its `return conversationBlob(…)`) unchanged into `private String appendV1(byte[] previous, ModelReply reply, List<ToolOutcome> outcomes)`.

- [ ] **Step 5: Update the call sites and test fixtures to the new signature**

In `CodingWorkflowImpl`, change the two call sites to keep compiling (Task 3 rewrites them):

```java
                conversation = activities.appendConversation(conversation, reply, java.util.List.of()).blobId();
...
            conversation = activities.appendConversation(conversation, reply, outcomes).blobId();
```

In each test fixture (`CodingWorkflowTest.ScriptedActivities`, `CodingWorkflowSmokeIT` fixture, `ApiWorkflowIT` fixture):

```java
        @Override public ConversationStep appendConversation(String previousBlobId, ModelReply reply,
                List<ToolOutcome> outcomes) { return new ConversationStep("2".repeat(64), null, null, null); }
        @Override public String resumeConversation(String blobId, String hint) { return blobId; }
```

Keep each fixture's existing blob value if it differs from `"2".repeat(64)`.

- [ ] **Step 6: Run the tests**

Run: `./gradlew test` and then `./gradlew integrationTest --tests 'com.brianmehrman.harness.execution.RepositoryTaskIT'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: return stall verdicts from conversation folds and freeze a pause policy"
```

---

### Task 3: Workflow pause, resume, and stop

**Files:**
- Create: `src/main/java/com/brianmehrman/harness/execution/RunStatus.java`
- Create: `src/main/java/com/brianmehrman/harness/execution/ResumeCommand.java`, `StopCommand.java`
- Modify: `src/main/java/com/brianmehrman/harness/execution/CodingWorkflow.java`
- Modify: `src/main/java/com/brianmehrman/harness/execution/CodingWorkflowImpl.java`
- Modify: terminal lists in `RunAdmissionStore.java:50`, `RunTelemetry.java:41`, `RunActivitiesImpl.java:113-118`, `RepositoryWorkService.java:154,159`, `TemporalWorkflowGateway.java:104` → use `RunStatus`
- Test: `src/test/java/com/brianmehrman/harness/execution/CodingWorkflowTest.java`

**Interfaces:**
- Consumes: `ConversationStep`, `PausePolicy`, `RunSpec.policy()`, `resumeConversation`, and `publishEvent` from Task 2.
- Produces:
  - `RunStatus.TERMINAL` (a `Set<String>`) and `RunStatus.isTerminal(String)`. Terminal statuses: `SUCCEEDED, READY_FOR_REVIEW, FAILED, CANCELLED, EXPIRED, LIMIT_EXHAUSTED, INFRASTRUCTURE_FAILED, CONTEXT_OVERFLOW, STALLED`.
  - `ResumeCommand(String commandId, long expectedVersion, int extraTurns, int extraToolCalls, int extraMinutes, String hint)`.
  - `StopCommand(String commandId, long expectedVersion)`.
  - `CodingWorkflow.resume(ResumeCommand)` and `stop(StopCommand)` as `@UpdateMethod`, each returning `CommandResult`. `deliveryStatus` is one of `APPLIED`, `REJECTED_STATE`, `REJECTED_TERMINAL`, `REJECTED_VERSION`, `REJECTED_CONFLICT`, `REJECTED_LIMIT`.
  - Events: `NUDGED {signal, turn, summary}`, `PAUSED {reason, signal, sinceTurn, evidence, note, turnsUsed, turnsGranted, toolCallsUsed, toolCallsGranted, activeMillisUsed, activeMillisGranted, note, waitUntil, summary}`, `RESUMED {commandId, extraTurns, extraToolCalls, extraMinutes, hint, summary}`, `STOPPED {reason, byOperator, summary}`.
  - Status `PAUSED`. After a resume, the status goes `QUEUED`, then `RUNNING`.

- [ ] **Step 1: Write the failing workflow test (RED)**

Change `twoTextOnlyRepliesFailAfterOneProtocolReminder`:
- rename it to `secondTextOnlyReplyStallsAndEndsWhenNobodyResumes`;
- change its assertion to `assertEquals("STALLED", workflow.run(spec).status());`;
- give `ScriptedActivities` a stall rule: `textOnly` counts replies with no tools in `appendConversation`, and the second returns `new ConversationStep(blob, null, new WorkingMemory.StallVerdict("NO_TOOL_CALL", 2, List.of("turn 2 reply had no tool call")))`;
- after the status, add `assertTrue(fixture.events.contains("PAUSED"));` and `assertTrue(fixture.events.contains("STOPPED"));`.

`lateModelResultCannotDispatchToolsAfterTheRunDeadline` keeps its assertions (`EXPIRED`, no tools). The time-skipping test service skips the 7-day wait. Add `assertTrue(fixture.events.contains("PAUSED"));`.

Add one new method:

```java
    @Test void turnLimitPausesThenResumesWithExtraTurnsAndHintThenSubmits() throws Exception {
        var fixture = new ScriptedActivities();
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker("coding-pause-test");
            worker.registerWorkflowImplementationTypes(UnversionedCodingWorkflow.class);
            worker.registerActivitiesImplementations(fixture);
            environment.start();
            var client = environment.getWorkflowClient();
            var workflow = client.newWorkflowStub(CodingWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId(RUN).setTaskQueue("coding-pause-test").build());
            var spec = new RunSpec(RUN, "start-1", "f".repeat(64), "task-tracker-v1",
                    "0".repeat(64), "evaluator-v1", "profile-1", "1".repeat(64), SEED,
                    Limits.codingDefaults().withModelTurns(2), PausePolicy.defaults());
            var result = WorkflowClient.execute(workflow::run, spec);

            RunView paused = awaitStatus(workflow, "PAUSED");
            assertTrue(fixture.released, "A paused run releases its execution slot");
            assertEquals("REJECTED_VERSION", workflow.resume(new ResumeCommand("resume-stale", 0, 3, 0, 0, null)).deliveryStatus());
            assertEquals("APPLIED", workflow.resume(new ResumeCommand("resume-1", paused.version(), 3, 0, 0,
                    "Use the passing approach")).deliveryStatus());
            assertEquals("APPLIED", workflow.resume(new ResumeCommand("resume-1", paused.version(), 3, 0, 0,
                    "Use the passing approach")).deliveryStatus(), "A repeated command returns its first outcome");

            assertEquals("SUCCEEDED", result.get(5, TimeUnit.SECONDS).status());
            assertEquals("Use the passing approach", fixture.hint);
            assertEquals(List.of("RUNNING", "PAUSED", "QUEUED", "RUNNING", "SUCCEEDED"), fixture.publishedStatuses);
            assertTrue(fixture.events.containsAll(List.of("PAUSED", "RESUMED")));

            var stopped = client.newWorkflowStub(CodingWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId(RUN + "-stop").setTaskQueue("coding-pause-test").build());
            var stopSpec = new RunSpec(RUN + "-stop", "start-2", "f".repeat(64), "task-tracker-v1",
                    "0".repeat(64), "evaluator-v1", "profile-1", "1".repeat(64), SEED,
                    Limits.codingDefaults().withModelTurns(1), PausePolicy.defaults());
            fixture.reset();
            var stopResult = WorkflowClient.execute(stopped::run, stopSpec);
            RunView stopPaused = awaitStatus(stopped, "PAUSED");
            assertEquals("APPLIED", stopped.stop(new StopCommand("stop-1", stopPaused.version())).deliveryStatus());
            assertEquals("LIMIT_EXHAUSTED", stopResult.get(5, TimeUnit.SECONDS).status());

            var cancelled = client.newWorkflowStub(CodingWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId(RUN + "-cancel").setTaskQueue("coding-pause-test").build());
            fixture.reset();
            var cancelResult = WorkflowClient.execute(cancelled::run, new RunSpec(RUN + "-cancel", "start-3",
                    "f".repeat(64), "task-tracker-v1", "0".repeat(64), "evaluator-v1", "profile-1",
                    "1".repeat(64), SEED, Limits.codingDefaults().withModelTurns(1), PausePolicy.defaults()));
            RunView cancelPaused = awaitStatus(cancelled, "PAUSED");
            assertEquals("APPLIED", cancelled.cancel(new CancelCommand("cancel-paused", cancelPaused.version())).deliveryStatus());
            assertEquals("CANCELLED", cancelResult.get(5, TimeUnit.SECONDS).status());
        }
    }

    private static RunView awaitStatus(CodingWorkflow workflow, String status) throws InterruptedException {
        for (int i = 0; i < 250; i++) {
            RunView view = workflow.current();
            if (status.equals(view.status())) return view;
            Thread.sleep(20);
        }
        throw new AssertionError("Workflow never reached " + status);
    }
```

`ScriptedActivities` additions:

```java
        private final List<String> events = new ArrayList<>();
        private String hint;
        private int textOnly;

        void reset() { modelCalls = 0; submissions = 0; toolSnapshots.clear(); publishedStatuses.clear(); events.clear(); }

        @Override public ConversationStep appendConversation(String previousBlobId, ModelReply reply,
                List<ToolOutcome> outcomes) {
            if (reply.tools().isEmpty() && ++textOnly >= 2)
                return new ConversationStep("2".repeat(64), null,
                        new WorkingMemory.StallVerdict("NO_TOOL_CALL", textOnly, List.of("turn reply had no tool call")), null);
            return new ConversationStep("2".repeat(64), null, null, null);
        }
        @Override public String resumeConversation(String blobId, String hint) { this.hint = hint; return blobId; }
        @Override public void publishEvent(String runId, long sequence, long version, String status,
                String snapshotSha256, String type, java.util.Map<String, Object> detail) { events.add(type); }
```

With `withModelTurns(2)`, the default script's first two replies (`read_file`, `write_file`) run, then the run pauses. Three extra turns run `run_tests`, `write_file + submit` (fails), and `write_file + submit` (passes). `released` becomes true at the pause.

- [ ] **Step 2: Run and confirm it fails**

Run: `./gradlew test --tests 'com.brianmehrman.harness.execution.CodingWorkflowTest'`
Expected: compilation FAIL, because `ResumeCommand`, `StopCommand`, `CodingWorkflow.resume`, and `CodingWorkflow.stop` do not exist.

- [ ] **Step 3: Add `RunStatus` and the commands, and switch the terminal lists**

`RunStatus.java`:

```java
package com.brianmehrman.harness.execution;

import java.util.Set;

/** The one list of terminal run statuses. */
public final class RunStatus {
    public static final Set<String> TERMINAL = Set.of("SUCCEEDED", "READY_FOR_REVIEW", "FAILED", "CANCELLED",
            "EXPIRED", "LIMIT_EXHAUSTED", "INFRASTRUCTURE_FAILED", "CONTEXT_OVERFLOW", "STALLED");
    private RunStatus() {}
    public static boolean isTerminal(String status) { return TERMINAL.contains(status); }
}
```

`ResumeCommand.java`:

```java
package com.brianmehrman.harness.execution;

public record ResumeCommand(String commandId, long expectedVersion, int extraTurns, int extraToolCalls,
        int extraMinutes, String hint) {
    public ResumeCommand {
        if (commandId == null || commandId.isBlank() || commandId.length() > 200 ||
                commandId.codePoints().anyMatch(Character::isISOControl) || expectedVersion < 0 ||
                extraTurns < 0 || extraToolCalls < 0 || extraMinutes < 0)
            throw new IllegalArgumentException("Invalid resume command");
        if (hint != null && (hint.isBlank() || hint.length() > 2000 ||
                hint.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')))
            throw new IllegalArgumentException("Invalid hint");
    }
}
```

`StopCommand.java`:

```java
package com.brianmehrman.harness.execution;

public record StopCommand(String commandId, long expectedVersion) {
    public StopCommand {
        if (commandId == null || commandId.isBlank() || commandId.length() > 200 ||
                commandId.codePoints().anyMatch(Character::isISOControl) || expectedVersion < 0)
            throw new IllegalArgumentException("Invalid stop command");
    }
}
```

Replace each terminal list:
- `CodingWorkflowImpl.terminalStatus` → `RunStatus.isTerminal(status)`.
- `RunAdmissionStore.reconcileCompleted` → `RunStatus.isTerminal(description.status()) || "WORKFLOW_EXECUTION_STATUS_COMPLETED".equals(description.status())`.
- `TemporalWorkflowGateway.terminal` → `RunStatus.isTerminal(status) || Set.of("WORKFLOW_EXECUTION_STATUS_COMPLETED", "WORKFLOW_EXECUTION_STATUS_FAILED", "WORKFLOW_EXECUTION_STATUS_CANCELED", "WORKFLOW_EXECUTION_STATUS_TERMINATED", "WORKFLOW_EXECUTION_STATUS_TIMED_OUT").contains(status)`.
- `RunTelemetry` → `RunStatus.isTerminal(status)`.
- `RunActivitiesImpl.callModel` → `RunStatus.isTerminal(status) || "CANCEL_REQUESTED".equals(status) || "STOPPING".equals(status)`.
- `RepositoryWorkService.detail` stale check → `RunStatus.isTerminal(row[5])`. Its unsubmitted-review list → `RunStatus.isTerminal(row[5]) && !"READY_FOR_REVIEW".equals(row[5]) && !"SUCCEEDED".equals(row[5])`.

- [ ] **Step 4: Rewrite the workflow loop**

`CodingWorkflow.java`, add:

```java
    @UpdateMethod CommandResult resume(ResumeCommand command);
    @UpdateMethod CommandResult stop(StopCommand command);
```

In `CodingWorkflowImpl.java`:
- Remove the fields `deadline`, `expired`, and `deadlineScope`, and the timer block in `run`.
- Replace the `cancellations` map with `controls` (cancel keeps its own map; resume and stop share `controls`).
- Add these fields:

```java
    private final Map<String, AcceptedControl> controls = new HashMap<>();
    private PausePolicy policy;
    private int turnsGranted, toolsGranted, attemptsGranted;
    private long activeMillisGranted, activeMillisUsed, segmentStartedAt;
    private int resumesApplied, extraTurnsApplied, extraToolCallsApplied, extraMinutesApplied;
    private boolean paused;
    private ResumeCommand resumeRequest;
    private boolean stopRequested;
    private String lastNote;
```

Replace `run(...)` with:

```java
    @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
    @Override public RunView run(RunSpec input) {
        spec = input;
        snapshot = input.seedSnapshotSha256();
        policy = input.policy();
        turnsGranted = input.limits().modelTurns();
        toolsGranted = input.limits().toolCalls();
        attemptsGranted = input.limits().modelAttempts();
        activeMillisGranted = input.limits().runMillis();
        RunView refused = admit();
        if (refused != null) return refused;
        transition("RUNNING");
        String conversation = activities.startConversation(input);
        int turn = 0;
        int toolCount = 0;
        int dispatched = 0;
        while (true) {
            if (cancelRequested) return terminal("CANCELLED");
            String limit = turn >= turnsGranted || dispatched + input.limits().perTurnModelAttempts() > attemptsGranted
                    ? "TURN_LIMIT" : outOfTime() ? "TIME_LIMIT" : null;
            if (limit != null) {
                Resumed resumed = pause(limit, null, conversation, turn, toolCount);
                if (resumed.terminal() != null) return resumed.terminal();
                conversation = resumed.conversation();
                continue;
            }
            turn++;
            String invocation = input.runId() + ":turn-" + turn;
            ModelReply reply = null;
            for (int attempt = 1; attempt <= input.limits().perTurnModelAttempts(); attempt++) {
                if (cancelRequested) return terminal("CANCELLED");
                dispatched++;
                long modelDeadline = Workflow.currentTimeMillis() + input.limits().modelAttemptMillis();
                try {
                    // A worker restart may redeliver this Activity to reconcile its durable receipt.
                    reply = timedActivities(modelDeadline, 2).callModel(new ModelCall(input.runId(), invocation, attempt,
                            input.profileRevision(), conversation, modelDeadline));
                    break;
                } catch (ActivityFailure error) {
                    if (cancelRequested) return terminal("CANCELLED");
                    String failureType = modelFailureType(error);
                    if ("MODEL_CONTEXT_OVERFLOW".equals(failureType)) return terminal("CONTEXT_OVERFLOW");
                    if (!"MODEL_ATTEMPT_UNKNOWN".equals(failureType)) return terminal("INFRASTRUCTURE_FAILED");
                }
            }
            if (reply == null) return terminal("INFRASTRUCTURE_FAILED");
            if ("repository-v1".equals(input.benchmarkVersion()))
                progress("MODEL_RECORDED", "model", "Model response recorded", null);
            var outcomes = new ArrayList<ToolOutcome>();
            String budgetPause = null;
            for (int index = 0; index < reply.tools().size(); index++) {
                if (cancelRequested) return terminal("CANCELLED");
                var request = reply.tools().get(index);
                String toolInvocation = invocation + ":tool-" + (index + 1);
                if (budgetPause == null)
                    budgetPause = toolCount >= toolsGranted ? "TOOL_LIMIT" : outOfTime() ? "TIME_LIMIT" : null;
                if (budgetPause != null) {
                    outcomes.add(notRun(toolInvocation, request));
                    continue;
                }
                toolCount++;
                long cap = switch (request.name()) {
                    case "run_tests", "build" -> input.limits().buildTestMillis();
                    case "submit" -> input.limits().evaluationMillis();
                    default -> input.limits().fileOperationMillis();
                };
                long toolDeadline = Workflow.currentTimeMillis() + cap;
                activeRunnerInvocation = "run_tests".equals(request.name()) || "build".equals(request.name())
                        ? toolInvocation : null;
                ToolOutcome outcome;
                try {
                    outcome = timedActivities(toolDeadline, 2).invokeTool(new ToolInvocation(input.runId(),
                            toolInvocation, new SnapshotRef(snapshot), request, toolDeadline));
                } catch (ActivityFailure failure) {
                    if (cancelRequested) return terminal("CANCELLED");
                    activeRunnerInvocation = null;
                    return terminal("INFRASTRUCTURE_FAILED");
                }
                if (cancelRequested) return terminal("CANCELLED");
                activeRunnerInvocation = null;
                snapshot = outcome.snapshot().sha256();
                outcomes.add(outcome);
                if ("repository-v1".equals(input.benchmarkVersion())) {
                    String stage = switch (request.name()) {
                        case "run_tests", "build" -> "check";
                        case "submit" -> "submit";
                        default -> "tool";
                    };
                    progress("TOOL_RECORDED", stage, request.name() + " result recorded", null);
                }
                if (outcome.failureCode() != null) return terminal("INFRASTRUCTURE_FAILED");
                if (outcome.attemptedSubmission() && outcome.passed())
                    return terminal("repository-v1".equals(input.benchmarkVersion()) ? "READY_FOR_REVIEW" : "SUCCEEDED");
            }
            ConversationStep step = activities.appendConversation(conversation, reply, outcomes);
            conversation = step.blobId();
            if (step.note() != null) lastNote = step.note();
            if (step.nudged() != null) event("NUDGED", Map.of("signal", step.nudged(), "turn", turn,
                    "summary", "Nudged: " + step.nudged().replace('_', ' ').toLowerCase()));
            String reason = budgetPause != null ? budgetPause : step.stall() != null ? "STALLED" : null;
            if (reason != null) {
                Resumed resumed = pause(reason, step.stall(), conversation, turn, toolCount);
                if (resumed.terminal() != null) return resumed.terminal();
                conversation = resumed.conversation();
            }
        }
    }
```

Add these helpers to `CodingWorkflowImpl`:

```java
    private record Resumed(RunView terminal, String conversation) {}
    private record AcceptedControl(Object payload, CommandResult result) {}

    private RunView admit() {
        while (!activities.acquireAdmission(spec.runId())) {
            if (cancelRequested) return terminal("CANCELLED");
            Workflow.sleep(Duration.ofSeconds(1));
        }
        if (cancelRequested) return terminal("CANCELLED");
        segmentStartedAt = Workflow.currentTimeMillis();
        return null;
    }

    private boolean outOfTime() {
        return activeMillisUsed + Workflow.currentTimeMillis() - segmentStartedAt >= activeMillisGranted;
    }

    private Resumed pause(String reason, WorkingMemory.StallVerdict stall, String conversation, int turn, int tools) {
        activeMillisUsed += Workflow.currentTimeMillis() - segmentStartedAt;
        activities.releaseAdmission(spec.runId());
        transition("PAUSED");
        var detail = new java.util.LinkedHashMap<String, Object>();
        detail.put("reason", reason);
        detail.put("signal", stall == null ? null : stall.signal());
        detail.put("sinceTurn", stall == null ? null : stall.sinceTurn());
        detail.put("evidence", stall == null ? java.util.List.of() : stall.evidence());
        detail.put("turnsUsed", turn);
        detail.put("turnsGranted", turnsGranted);
        detail.put("toolCallsUsed", tools);
        detail.put("toolCallsGranted", toolsGranted);
        detail.put("activeMillisUsed", activeMillisUsed);
        detail.put("activeMillisGranted", activeMillisGranted);
        detail.put("note", lastNote);
        detail.put("waitUntil", Instant.ofEpochMilli(Workflow.currentTimeMillis() + policy.waitMillis()).toString());
        detail.put("summary", "Paused: " + reason.replace('_', ' ').toLowerCase());
        event("PAUSED", detail);
        resumeRequest = null;
        stopRequested = false;
        paused = true;
        boolean signalled = Workflow.await(Duration.ofMillis(policy.waitMillis()),
                () -> resumeRequest != null || stopRequested || cancelRequested);
        paused = false;
        if (cancelRequested) return new Resumed(terminal("CANCELLED"), null);
        if (!signalled || stopRequested) {
            event("STOPPED", Map.of("reason", reason, "byOperator", signalled,
                    "summary", signalled ? "Stopped by operator" : "Stopped: waiting limit reached"));
            return new Resumed(terminal(switch (reason) {
                case "STALLED" -> "STALLED";
                case "TIME_LIMIT" -> "EXPIRED";
                default -> "LIMIT_EXHAUSTED";
            }), null);
        }
        ResumeCommand resume = resumeRequest;
        turnsGranted += resume.extraTurns();
        attemptsGranted += 2 * resume.extraTurns();
        toolsGranted += resume.extraToolCalls();
        activeMillisGranted += resume.extraMinutes() * 60_000L;
        String next = activities.resumeConversation(conversation, resume.hint());
        var resumed = new java.util.LinkedHashMap<String, Object>();
        resumed.put("commandId", resume.commandId());
        resumed.put("extraTurns", resume.extraTurns());
        resumed.put("extraToolCalls", resume.extraToolCalls());
        resumed.put("extraMinutes", resume.extraMinutes());
        resumed.put("hint", resume.hint());
        resumed.put("summary", resume.hint() == null ? "Resumed with more budget" : "Resumed with an operator hint");
        event("RESUMED", resumed);
        transition("QUEUED");
        RunView refused = admit();
        if (refused != null) return new Resumed(refused, null);
        transition("RUNNING");
        return new Resumed(null, next);
    }

    private ToolOutcome notRun(String invocationId, com.brianmehrman.harness.model.ToolRequest request) {
        return new ToolOutcome(invocationId, request.providerCallId(), request.name(), new SnapshotRef(snapshot),
                "{\"status\":\"ERROR\",\"code\":\"BUDGET_PAUSED\",\"detail\":\"Not run: the run paused at its budget. " +
                        "Repeat this call after the run resumes.\"}", false, false);
    }

    private void event(String type, Map<String, Object> detail) {
        sequence++;
        activities.publishEvent(spec.runId(), sequence, version, status, snapshot, type, detail);
    }

    @Override public CommandResult resume(ResumeCommand command) {
        return control(command.commandId(), command.expectedVersion(), command, () -> {
            if (resumesApplied >= policy.maxResumes() || command.extraTurns() > policy.resumeTurns() ||
                    command.extraToolCalls() > policy.resumeToolCalls() || command.extraMinutes() > policy.resumeMinutes() ||
                    extraTurnsApplied + command.extraTurns() > policy.totalExtraTurns() ||
                    extraToolCallsApplied + command.extraToolCalls() > policy.totalExtraToolCalls() ||
                    extraMinutesApplied + command.extraMinutes() > policy.totalExtraMinutes())
                return "REJECTED_LIMIT";
            resumesApplied++;
            extraTurnsApplied += command.extraTurns();
            extraToolCallsApplied += command.extraToolCalls();
            extraMinutesApplied += command.extraMinutes();
            resumeRequest = command;
            return "APPLIED";
        });
    }

    @Override public CommandResult stop(StopCommand command) {
        return control(command.commandId(), command.expectedVersion(), command, () -> {
            stopRequested = true;
            return "APPLIED";
        });
    }

    private CommandResult control(String commandId, long expectedVersion, Object payload,
            java.util.function.Supplier<String> apply) {
        var prior = controls.get(commandId);
        if (prior != null) return prior.payload().equals(payload) ? prior.result()
                : new CommandResult(spec.runId(), commandId, "REJECTED_CONFLICT");
        if (RunStatus.isTerminal(status)) return new CommandResult(spec.runId(), commandId, "REJECTED_TERMINAL");
        if (!paused || resumeRequest != null || stopRequested)
            return new CommandResult(spec.runId(), commandId, "REJECTED_STATE");
        if (expectedVersion != version) return new CommandResult(spec.runId(), commandId, "REJECTED_VERSION");
        var result = new CommandResult(spec.runId(), commandId, apply.get());
        controls.put(commandId, new AcceptedControl(payload, result));
        return result;
    }
```

In `terminal(...)`, remove `if (deadlineScope != null) deadlineScope.cancel();`. Keep the `STOPPING` runner cleanup for `CANCELLED` and `EXPIRED`. `releaseAdmission` after a pause is harmless: it deletes nothing if the slot is already free. The `cancel` update stays as it is. While paused, its `cancelRequested = true` wakes the `Workflow.await`.

Remove the now-unused imports (`Async`, `CancellationScope`, `CanceledFailure`) and the `remindedOfProtocol` variable.

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `./gradlew test --tests 'com.brianmehrman.harness.execution.CodingWorkflowTest'`
Expected: PASS (5 tests: 1 new, 1 renamed). Then run `./gradlew test`. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: pause coding runs at limits and stalls, and resume or stop them"
```

---

### Task 4: Durable delivery of resume and stop

**Files:**
- Create: `src/main/resources/db/migration/V9__allow_resume_and_stop_commands.sql`
- Modify: `src/main/java/com/brianmehrman/harness/runs/RunCommandService.java`
- Modify: `src/main/java/com/brianmehrman/harness/runs/CommandDispatcher.java`
- Modify: `src/main/java/com/brianmehrman/harness/runs/WorkflowGateway.java`
- Modify: `src/main/java/com/brianmehrman/harness/runs/TemporalWorkflowGateway.java`
- Modify: `src/main/java/com/brianmehrman/harness/execution/RunAdmissionStore.java`
- Test: `src/test/java/com/brianmehrman/harness/api/WorkApiIT.java` (extend `admissionWaitsForOlderDeliveredWork`)

**Interfaces:**
- Consumes: `ResumeCommand`, `StopCommand`, `CodingWorkflow.resume`, and `CodingWorkflow.stop` from Task 3; `RunSpec.policy()` from Task 2.
- Produces:
  - `RunCommandService.resume(String runId, ResumeCommand)` → `CommandResult`. Throws `IllegalArgumentException("Run is not paused")` or `IllegalArgumentException("Resume exceeds ...")`.
  - `RunCommandService.stop(String runId, StopCommand)` → `CommandResult`.
  - `WorkflowGateway.resume(String runId, ResumeCommand)` and `stop(String runId, StopCommand)`, each with a default that throws `UnsupportedOperationException`.

- [ ] **Step 1: Extend the admission test (RED)**

At the end of `WorkApiIT.admissionWaitsForOlderDeliveredWork`, append:

```java
        // A resumed run rejoins the queue by its resume time, behind work that queued earlier.
        String thirdCommand = "work-order-" + UUID.randomUUID();
        String third = create(thirdCommand, profile);
        jdbc.update("update run_command set delivered_at=now() where command_id=?", thirdCommand);
        jdbc.update("update run_projection set status='QUEUED' where run_id=?", first);
        jdbc.update("insert into run_command(command_id,run_id,kind,payload_sha256,payload_json,delivered_at,outcome_json) " +
                        "values (?,?,'RESUME',?,'{}'::jsonb,now(),?::jsonb)",
                "resume-" + UUID.randomUUID(), first, "0".repeat(64),
                "{\"runId\":\"" + first + "\",\"commandId\":\"x\",\"deliveryStatus\":\"APPLIED\"}");
        assertFalse(admission.acquire(first), "The resumed run waits behind work queued before its resume");
        assertTrue(admission.acquire(third));
        assertTrue(admission.releaseAfterTerminalTransition(third));
        assertTrue(admission.acquire(first));
        assertTrue(admission.releaseAfterTerminalTransition(first));
```

- [ ] **Step 2: Run and confirm it fails**

Run: `./gradlew integrationTest --tests 'com.brianmehrman.harness.api.WorkApiIT'`
Expected: FAIL. The insert violates `run_command_kind_check` (`RESUME` is not allowed).

- [ ] **Step 3: Add the migration**

First confirm the constraint name:

```bash
docker compose -p harness-tryout exec -T postgres psql -U postgres -d harness -At -c \
  "select conname from pg_constraint where conrelid='run_command'::regclass and contype='c' and pg_get_constraintdef(oid) like '%kind%';"
```

Expected: `run_command_kind_check`. Use the printed name if it differs.

`V9__allow_resume_and_stop_commands.sql`:

```sql
ALTER TABLE run_command DROP CONSTRAINT run_command_kind_check;
ALTER TABLE run_command ADD CONSTRAINT run_command_kind_check
    CHECK (kind IN ('START','CANCEL','RESUME','STOP'));
```

- [ ] **Step 4: Order admission by resume time**

In `RunAdmissionStore.acquire`, replace `order by c.accepted_at,c.command_id limit 1` with:

```java
                        "order by coalesce((select max(rc.accepted_at) from run_command rc where rc.run_id=r.run_id " +
                        "and rc.kind='RESUME' and rc.outcome_json->>'deliveryStatus'='APPLIED'), c.accepted_at)," +
                        "c.command_id limit 1"
```

- [ ] **Step 5: Run the admission test**

Run: `./gradlew integrationTest --tests 'com.brianmehrman.harness.api.WorkApiIT'`
Expected: PASS.

- [ ] **Step 6: Generalize command acceptance and delivery**

In `RunCommandService`, replace the body of `cancel` with a call to a shared method, and add `resume` and `stop`:

```java
    public CommandResult cancel(String runId, CancelCommand command) {
        Objects.requireNonNull(command);
        return control(runId, "CANCEL", command.commandId(), command);
    }

    public CommandResult resume(String runId, ResumeCommand command) {
        Objects.requireNonNull(command);
        if (!exists(command.commandId())) {
            requirePaused(runId);
            PausePolicy policy = json.readValue(jdbc.queryForObject("select spec_json::text from run_request where run_id=?",
                    String.class, runId), RunSpec.class).policy();
            if (command.extraTurns() > policy.resumeTurns() || command.extraToolCalls() > policy.resumeToolCalls() ||
                    command.extraMinutes() > policy.resumeMinutes())
                throw new IllegalArgumentException("Resume exceeds the per-resume ceiling");
            var prior = jdbc.query("select payload_json::text from run_command where run_id=? and kind='RESUME' " +
                            "and (delivered_at is null or outcome_json->>'deliveryStatus'='APPLIED')",
                    (rs, row) -> json.readValue(rs.getString(1), ResumeCommand.class), runId);
            if (prior.size() + 1 > policy.maxResumes() ||
                    prior.stream().mapToInt(ResumeCommand::extraTurns).sum() + command.extraTurns() > policy.totalExtraTurns() ||
                    prior.stream().mapToInt(ResumeCommand::extraToolCalls).sum() + command.extraToolCalls() > policy.totalExtraToolCalls() ||
                    prior.stream().mapToInt(ResumeCommand::extraMinutes).sum() + command.extraMinutes() > policy.totalExtraMinutes())
                throw new IllegalArgumentException("Resume exceeds the run's extension ceiling");
        }
        return control(runId, "RESUME", command.commandId(), command);
    }

    public CommandResult stop(String runId, StopCommand command) {
        Objects.requireNonNull(command);
        if (!exists(command.commandId())) requirePaused(runId);
        return control(runId, "STOP", command.commandId(), command);
    }

    private boolean exists(String commandId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from run_command where command_id=?)",
                Boolean.class, commandId));
    }

    private void requirePaused(String runId) {
        var status = jdbc.queryForList("select status from run_projection where run_id=?", String.class, runId);
        if (status.isEmpty()) throw new IllegalArgumentException("Unknown run");
        if (!"PAUSED".equals(status.getFirst())) throw new IllegalArgumentException("Run is not paused");
    }

    private CommandResult control(String runId, String kind, String commandId, Object command) {
        identity(runId);
        String payload = json.writeValueAsString(command);
        String digest = SnapshotHasher.content("run-" + kind.toLowerCase() + "-v1\n" + runId + "\n" + payload);
        return transactions.execute(status -> {
            var previous = jdbc.query("select run_id,kind,payload_sha256,delivered_at is not null " +
                            "from run_command where command_id=?",
                    (rs, row) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)},
                    commandId);
            if (!previous.isEmpty()) return existingControl(runId, kind, commandId, digest, previous.getFirst());
            if (jdbc.queryForObject("select count(*) from run_request where run_id=?", Integer.class, runId) == 0)
                throw new IllegalArgumentException("Unknown run");
            jdbc.update("insert into run_command(command_id,run_id,kind,payload_sha256,payload_json) " +
                            "values (?,?,?,?,?::jsonb) on conflict do nothing",
                    commandId, runId, kind, digest, payload);
            var accepted = jdbc.query("select run_id,kind,payload_sha256,delivered_at is not null " +
                            "from run_command where command_id=?",
                    (rs, row) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)},
                    commandId);
            return existingControl(runId, kind, commandId, digest, accepted.getFirst());
        });
    }

    private static CommandResult existingControl(String runId, String kind, String commandId, String digest, Object[] row) {
        if (!runId.equals(row[0]) || !kind.equals(row[1]) || !digest.equals(row[2]))
            throw new IllegalArgumentException(kind.charAt(0) + kind.substring(1).toLowerCase() +
                    " command ID reused with different input");
        return new CommandResult(runId, commandId, (Boolean) row[3] ? "DELIVERED" : "ACCEPTED");
    }
```

Delete the old `existingCancel`. The cancel digest prefix stays `run-cancel-v1`, so stored cancel rows still match.

`WorkflowGateway`:

```java
    default CommandResult resume(String runId, ResumeCommand command) {
        throw new UnsupportedOperationException("Resume delivery not available");
    }
    default CommandResult stop(String runId, StopCommand command) {
        throw new UnsupportedOperationException("Stop delivery not available");
    }
```

`TemporalWorkflowGateway`: refactor `cancel`'s body into a helper and reuse it:

```java
    @Override public CommandResult cancel(String runId, CancelCommand command) {
        return update(runId, command.commandId(), stub -> stub.cancel(command));
    }
    @Override public CommandResult resume(String runId, ResumeCommand command) {
        return update(runId, command.commandId(), stub -> stub.resume(command));
    }
    @Override public CommandResult stop(String runId, StopCommand command) {
        return update(runId, command.commandId(), stub -> stub.stop(command));
    }

    private CommandResult update(String runId, String commandId,
            java.util.function.Function<CodingWorkflow, CommandResult> call) {
        WorkflowDescription described = describe(runId);
        if (described == null) throw new IllegalArgumentException("Unknown workflow");
        if (terminal(described.status())) return new CommandResult(runId, commandId, "REJECTED_TERMINAL");
        try {
            return call.apply(client.newWorkflowStub(CodingWorkflow.class, runId));
        } catch (WorkflowNotFoundException closedDuringUpdate) {
            WorkflowDescription latest = describe(runId);
            if (latest != null && terminal(latest.status()))
                return new CommandResult(runId, commandId, "REJECTED_TERMINAL");
            throw closedDuringUpdate;
        }
    }
```

In `start(...)`, change `.setWorkflowExecutionTimeout(Duration.ofDays(8))` to `Duration.ofDays(40)`.

`CommandDispatcher.dispatchOnce`, switch:

```java
                case "CANCEL" -> deliver(claim, json.readValue(claim.payloadJson(), CancelCommand.class),
                        command -> gateway.cancel(claim.runId(), command));
                case "RESUME" -> deliver(claim, json.readValue(claim.payloadJson(), ResumeCommand.class),
                        command -> gateway.resume(claim.runId(), command));
                case "STOP" -> deliver(claim, json.readValue(claim.payloadJson(), StopCommand.class),
                        command -> gateway.stop(claim.runId(), command));
```

Replace `deliverCancel` with:

```java
    private <T> String deliver(Claim claim, T command, java.util.function.Function<T, CommandResult> send) {
        CommandResult result = send.apply(command);
        if (result == null || !claim.runId().equals(result.runId()) ||
                !claim.commandId().equals(result.commandId()))
            throw new IllegalStateException("Workflow command identity mismatch");
        return json.writeValueAsString(result);
    }
```

- [ ] **Step 7: Run the tests**

Run: `./gradlew test` and `./gradlew integrationTest --tests 'com.brianmehrman.harness.api.WorkApiIT' --tests 'com.brianmehrman.harness.api.RunApiIT' --tests 'com.brianmehrman.harness.api.ApiWorkflowIT'`
Expected: PASS. Existing cancel tests must still pass, including the reused-ID conflict message, which now reads "Cancel command ID reused with different input".

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/db/migration/V9__allow_resume_and_stop_commands.sql src/main/java src/test/java
git commit -m "feat: deliver resume and stop commands durably and requeue resumed runs fairly"
```

Before committing, verify the migration filename and that Flyway applied it: `docker compose -p harness-tryout exec -T postgres psql -U postgres -d harness -At -c "select version,description from flyway_schema_history order by installed_rank desc limit 1;"` → `9|allow resume and stop commands` (in the schema the IT used).

---

### Task 5: API routes and pause data in run and work views

**Files:**
- Create: `src/main/java/com/brianmehrman/harness/runs/PauseHistory.java`
- Modify: `src/main/java/com/brianmehrman/harness/runs/RunQueryService.java` (`detail`)
- Modify: `src/main/java/com/brianmehrman/harness/runs/RepositoryWorkService.java` (`detail`, `list`, `events`)
- Modify: `src/main/java/com/brianmehrman/harness/api/RunController.java`, `WorkController.java`
- Test: `src/test/java/com/brianmehrman/harness/api/RunApiIT.java` (extend `duplicateStartOrderedEventsAndVersionedCancel`)

**Interfaces:**
- Consumes: `RunCommandService.resume` and `stop` (Task 4); the event types from Task 3.
- Produces:
  - `PauseHistory.read(JdbcTemplate, BlobStore, String runId, String status)` returns a `Map` with `pause`, `resumes`, `nudges`, and `label`.
  - Routes: `POST /api/runs/{id}/resume`, `/stop`, `/api/work/{id}/resume`, `/stop`.
  - The work list gains `paused` (work items) and `pausedRuns` (`{runId, status, updatedAt}` for benchmark runs).

- [ ] **Step 1: Extend the API test (RED)**

In `RunApiIT.duplicateStartOrderedEventsAndVersionedCancel`, insert this block immediately after the line `assertEquals(202, request("POST", "/api/runs/" + runId + "/cancel", cancel).statusCode());`. It must come before the test marks the START command delivered, because a delivered START makes the version check query Temporal, which this test does not run:

```java
        String resume = json.writeValueAsString(Map.of("commandId", "resume-" + UUID.randomUUID(),
                "expectedVersion", 2, "extraTurns", 5));
        assertEquals(409, request("POST", "/api/runs/" + runId + "/resume", resume).statusCode(), "Only a paused run resumes");
        typedEvent(runId, 3, 3, "PAUSED", "PAUSED", Map.of("reason", "TURN_LIMIT", "evidence", List.of(),
                "turnsUsed", 40, "turnsGranted", 40, "summary", "Paused: turn limit"));
        String tooMuch = json.writeValueAsString(Map.of("commandId", "resume-" + UUID.randomUUID(),
                "expectedVersion", 3, "extraTurns", 41));
        assertEquals(400, request("POST", "/api/runs/" + runId + "/resume", tooMuch).statusCode());
        String resumeId = "resume-" + UUID.randomUUID();
        String good = json.writeValueAsString(Map.of("commandId", resumeId, "expectedVersion", 3,
                "extraTurns", 5, "hint", "Fix the definite-assignment error first"));
        assertEquals(202, request("POST", "/api/runs/" + runId + "/resume", good).statusCode());
        assertEquals(202, request("POST", "/api/runs/" + runId + "/resume", good).statusCode(), "Same command, same answer");
        assertEquals(409, request("POST", "/api/runs/" + runId + "/resume",
                good.replace("first", "second")).statusCode(), "A reused ID with different input conflicts");
        String stop = json.writeValueAsString(Map.of("commandId", "stop-" + UUID.randomUUID(), "expectedVersion", 3));
        assertEquals(202, request("POST", "/api/runs/" + runId + "/stop", stop).statusCode());
        JsonNode paused = json.readTree(request("GET", "/api/runs/" + runId, null).body());
        assertEquals("TURN_LIMIT", paused.path("pause").path("reason").asText());
        assertEquals("RESUME", paused.path("commands").get(2).path("kind").asText());
```

Add the helper next to `event(...)`:

```java
    private void typedEvent(String runId, long sequence, long version, String status, String type,
            Map<String, Object> detail) {
        var payload = new java.util.LinkedHashMap<String, Object>(detail);
        payload.put("version", version);
        payload.put("status", status);
        String digest = blobs.put("application/vnd.harness.run-event+json", json.writeValueAsBytes(payload));
        jdbc.update("insert into run_event(run_id,sequence,event_id,type,payload_blob_id) values (?,?,?,?,?)",
                runId, sequence, runId + ":api-test-" + sequence, type, digest);
        jdbc.update("update run_projection set version=?,status=?,last_sequence=? where run_id=?",
                version, status, sequence, runId);
    }
```

Add `import java.util.List;`.

- [ ] **Step 2: Run and confirm it fails**

Run: `./gradlew integrationTest --tests 'com.brianmehrman.harness.api.RunApiIT'`
Expected: FAIL. `/resume` returns 404 or 405, not 409.

- [ ] **Step 3: Add `PauseHistory`**

```java
package com.brianmehrman.harness.runs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Pause, resume, and nudge history read from durable run events. */
public final class PauseHistory {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private PauseHistory() {}

    public static Map<String, Object> read(JdbcTemplate jdbc, BlobStore blobs, String runId, String status) {
        Map<String, Object> pause = null;
        var resumes = new ArrayList<Map<String, Object>>();
        int nudges = 0;
        for (var row : jdbc.queryForList("select type,payload_blob_id from run_event where run_id=? " +
                "and type in ('PAUSED','RESUMED','NUDGED') order by sequence", runId)) {
            Map<String, Object> payload = JSON.readValue(blobs.get((String) row.get("payload_blob_id")),
                    new TypeReference<>() {});
            switch ((String) row.get("type")) {
                case "PAUSED" -> pause = payload;
                case "RESUMED" -> resumes.add(payload);
                default -> nudges++;
            }
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("pause", "PAUSED".equals(status) ? pause : null);
        result.put("resumes", resumes);
        result.put("nudges", nudges);
        result.put("label", resumes.stream().anyMatch(r -> r.get("hint") != null) ? "assisted"
                : resumes.isEmpty() ? null : "extended");
        return result;
    }
}
```

- [ ] **Step 4: Expose the history and add the routes**

`RunQueryService.detail`: build a mutable map instead of `Map.of`:

```java
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("projection", view);
        result.put("commands", commands);
        result.putAll(PauseHistory.read(jdbc, blobs, runId, view.status()));
        return result;
```

`RepositoryWorkService.detail`: before `return result;`, add `result.putAll(PauseHistory.read(jdbc, blobs, runId, (String) row[5]));`.

`RepositoryWorkService.list`:
- In the row loop, add `else if (row.status().equals("PAUSED")) paused.add(row);` before `else history.add(row);`, with `List<WorkSummary> paused = new ArrayList<>();`.
- Add these to `result`:

```java
        result.put("paused", paused);
        result.put("pausedRuns", jdbc.query("select r.run_id,p.status,p.updated_at from run_request r " +
                        "join run_projection p on p.run_id=r.run_id where r.kind='BENCHMARK' and p.status='PAUSED' " +
                        "order by p.updated_at",
                (rs, n) -> Map.of("runId", rs.getString(1), "status", rs.getString(2),
                        "updatedAt", rs.getTimestamp(3).toInstant().toString())));
```

`RepositoryWorkService.events`, stage mapping: add `"PAUSED".equals(status) ? "paused" :` before the `"terminal"` fallback for `STATE_CHANGED`. For event types `PAUSED`, `RESUMED`, `NUDGED`, and `STOPPED`, use `payload.path("summary").asText()` as the summary and `"paused"` (for `PAUSED`) or `payload.path("stage").asText("model")` as the stage.

`RunController`: add these fields and routes:

```java
    private static final Set<String> RESUME_REQUIRED = Set.of("commandId", "expectedVersion");
    private static final Set<String> RESUME_OPTIONAL = Set.of("extraTurns", "extraToolCalls", "extraMinutes", "hint");

    @PostMapping(value = "/api/runs/{runId}/resume", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> resume(@PathVariable String runId, @RequestBody String body) {
        ResumeCommand command = resumeCommand(body);
        return accepted(runId, command.commandId(), command.expectedVersion(), () -> commands.resume(runId, command));
    }

    @PostMapping(value = "/api/runs/{runId}/stop", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> stop(@PathVariable String runId, @RequestBody String body) {
        Map<String, Object> data = object(body, CANCEL);
        StopCommand command;
        try { command = new StopCommand(string(data, "commandId"), version(data)); }
        catch (IllegalArgumentException invalid) { throw new BadInput(invalid.getMessage()); }
        return accepted(runId, command.commandId(), command.expectedVersion(), () -> commands.stop(runId, command));
    }

    static ResumeCommand resumeCommand(String body) {
        Map<String, Object> data = object(body, RESUME_REQUIRED, RESUME_OPTIONAL);
        try {
            return new ResumeCommand(string(data, "commandId"), version(data), count(data, "extraTurns"),
                    count(data, "extraToolCalls"), count(data, "extraMinutes"),
                    data.get("hint") == null ? null : string(data, "hint"));
        } catch (IllegalArgumentException invalid) { throw new BadInput(invalid.getMessage()); }
    }

    private ResponseEntity<?> accepted(String runId, String commandId, long expectedVersion,
            java.util.function.Supplier<CommandResult> accept) {
        try {
            if (!queries.commandExists(runId, commandId) && expectedVersion != queries.currentVersion(runId))
                throw new Conflict("Expected version differs from current run version");
            var accepted = accept.get();
            var state = queries.command(accepted.runId(), accepted.commandId());
            return ResponseEntity.accepted().body(new CommandResponse(accepted.runId(), accepted.commandId(),
                    state.deliveryStatus(), state.applicationOutcome()));
        } catch (IllegalArgumentException invalid) { throw classify(invalid); }
    }

    static Map<String, Object> object(String body, Set<String> required, Set<String> optional) {
        try {
            Map<String, Object> data = JsonMapper.builder().build().readValue(body, new TypeReference<>() {});
            if (data == null || !data.keySet().containsAll(required) ||
                    !data.keySet().stream().allMatch(k -> required.contains(k) || optional.contains(k)))
                throw new BadInput("Unexpected or missing fields");
            return data;
        } catch (BadInput invalid) { throw invalid; }
        catch (RuntimeException invalid) { throw new BadInput("Malformed JSON object"); }
    }

    static long version(Map<String, Object> data) {
        if (!(data.get("expectedVersion") instanceof Number number) ||
                !(number instanceof Integer || number instanceof Long)) throw new BadInput("Invalid expectedVersion");
        return number.longValue();
    }

    static int count(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (value == null) return 0;
        if (!(value instanceof Integer number) || number < 0) throw new BadInput("Invalid " + field);
        return number;
    }
```

In `classify`, add `if (message.startsWith("Run is not paused")) return new Conflict(message);` before the `reused` check. A message starting `Resume exceeds` falls through to `BadInput` (400).

`WorkController`: add `/api/work/{workId}/resume` and `/stop`. They parse with `RunController.resumeCommand(body)` (or the stop form), check that `work.detail(workId).get("deliveryStatus")` is `DELIVERED` (otherwise throw `Conflict("Run is not paused")`), then call `commands.resume(workId, command)` or `commands.stop(...)`. Map exceptions as `cancel` does, and map "Run is not paused" to `Conflict`. Return `WorkAccepted(workId, commandId, deliveryStatus)`.

- [ ] **Step 5: Run the tests**

Run: `./gradlew integrationTest --tests 'com.brianmehrman.harness.api.RunApiIT' --tests 'com.brianmehrman.harness.api.WorkApiIT'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java src/test/java
git commit -m "feat: resume and stop routes with pause history in run and work views"
```

---

### Task 6: CLI resume, stop, and pause status

**Files:**
- Modify: `scripts/harness.py`
- Test: manual command check (no Python test suite exists for the CLI)

- [ ] **Step 1: Add the subcommands**

In `parser()`:

```python
    resume = commands.add_parser("resume")
    resume.add_argument("run_id")
    resume.add_argument("--expected-version", type=int, required=True)
    resume.add_argument("--command-id", default=None)
    resume.add_argument("--turns", type=int, default=0)
    resume.add_argument("--tools", type=int, default=0)
    resume.add_argument("--minutes", type=int, default=0)
    resume.add_argument("--hint", default=None)
    stop = commands.add_parser("stop")
    stop.add_argument("run_id")
    stop.add_argument("--expected-version", type=int, required=True)
    stop.add_argument("--command-id", default=None)
```

In `main()`, before `elif args.command == "download":`:

```python
    elif args.command == "resume":
        body = {"commandId": args.command_id or str(uuid.uuid4()), "expectedVersion": args.expected_version,
                "extraTurns": args.turns, "extraToolCalls": args.tools, "extraMinutes": args.minutes}
        if args.hint is not None:
            body["hint"] = args.hint
        result = client.request("POST", f"/api/runs/{quote(args.run_id)}/resume", body)
    elif args.command == "stop":
        result = client.request("POST", f"/api/runs/{quote(args.run_id)}/stop", {
            "commandId": args.command_id or str(uuid.uuid4()),
            "expectedVersion": args.expected_version,
        })
```

`status RUN` already prints the whole detail JSON, which now includes `pause`, `resumes`, `nudges`, and `label`.

- [ ] **Step 2: Check the parser**

Run: `python3 scripts/harness.py resume --help && python3 scripts/harness.py stop --help`
Expected: both print usage with the new options and exit 0.

- [ ] **Step 3: Commit**

```bash
git add scripts/harness.py
git commit -m "feat: resume and stop paused runs from the CLI"
```

---

### Task 7: Console banner, Paused node, and pause panel

**Files:**
- Modify: `web/src/api.ts`, `web/src/App.tsx`, `web/src/styles.css`
- Test: `cd web && npm run build` (type check and bundle), plus the manual browser check in Task 8

- [ ] **Step 1: Types and calls in `api.ts`**

```ts
export type Pause = { reason: string; signal: string | null; sinceTurn: number | null; evidence: string[]; note: string | null; turnsUsed: number; turnsGranted: number; toolCallsUsed: number; toolCallsGranted: number; activeMillisUsed: number; activeMillisGranted: number; waitUntil: string; summary: string };
export type Resume = { commandId: string; extraTurns: number; extraToolCalls: number; extraMinutes: number; hint: string | null };
```

- Extend `WorkDetail` with `pause: Pause | null; resumes: Resume[]; nudges: number; label: 'assisted' | 'extended' | null`.
- Extend `WorkList` with `paused: WorkSummary[]; pausedRuns: { runId: string; status: string; updatedAt: string }[]`.
- Add these to `api`:

```ts
  resume: (id: string, body: { commandId: string; expectedVersion: number; extraTurns: number; extraToolCalls: number; extraMinutes: number; hint?: string }) => request<unknown>(`/api/work/${encodeURIComponent(id)}/resume`, { method: 'POST', body: JSON.stringify(body) }),
  stop: (id: string, commandId: string, expectedVersion: number) => request<unknown>(`/api/work/${encodeURIComponent(id)}/stop`, { method: 'POST', body: JSON.stringify({ commandId, expectedVersion }) }),
```

- [ ] **Step 2: App changes**

In `App.tsx`:
- `stages`: insert `'Paused'` before `'Terminal'`. Set `currentStage` to the Paused index when `detail?.status === 'PAUSED'`.
- `terminal` set: add `'STALLED'`.
- Initial `list` state: add `paused: [], pausedRuns: []`.
- Banner, rendered right after the `error` alert:

```tsx
      {(list.paused.length + list.pausedRuns.length) > 0 && <div className="alert paused-banner" role="status">
        {list.paused.length + list.pausedRuns.length} paused · waiting for you:{' '}
        {list.paused.map(item => <button key={item.workId} className="link" onClick={() => choose(item.workId)}>{item.title}</button>)}
        {list.pausedRuns.map(run => <a key={run.runId} href={`/api/runs/${encodeURIComponent(run.runId)}`}>benchmark {short(run.runId)} ↗</a>)}
      </div>}
```

- A Paused section in the left column, after "Working now", listing `list.paused` with `WorkRow`.
- Pause panel inside the detail aside, after the status line, when `detail.status === 'PAUSED' && detail.pause`:

```tsx
            <section className="pause-panel" aria-label="Paused run">
              <h4>Paused · {detail.pause.reason.replaceAll('_', ' ').toLowerCase()}{detail.pause.signal ? ` (${detail.pause.signal.replaceAll('_', ' ').toLowerCase()})` : ''}</h4>
              {detail.pause.evidence.length > 0 && <ol className="events">{detail.pause.evidence.map((line, i) => <li key={i}>{line}</li>)}</ol>}
              {detail.pause.note && <p className="instructions">{detail.pause.note}</p>}
              <p className="instructions">Turns {detail.pause.turnsUsed}/{detail.pause.turnsGranted} · tool calls {detail.pause.toolCallsUsed}/{detail.pause.toolCallsGranted} · active {Math.round(detail.pause.activeMillisUsed / 60000)}/{Math.round(detail.pause.activeMillisGranted / 60000)} min · waits until {new Date(detail.pause.waitUntil).toLocaleString()}</p>
              <form onSubmit={resume}>
                <label>Extra turns (max 40)<input type="number" min={0} max={40} value={extra.turns} onChange={e => setExtra({ ...extra, turns: Number(e.target.value) })} /></label>
                <label>Extra tool calls (max 100)<input type="number" min={0} max={100} value={extra.tools} onChange={e => setExtra({ ...extra, tools: Number(e.target.value) })} /></label>
                <label>Extra minutes (max 30)<input type="number" min={0} max={30} value={extra.minutes} onChange={e => setExtra({ ...extra, minutes: Number(e.target.value) })} /></label>
                <label>Hint for the agent (optional; marks the run assisted)<textarea maxLength={2000} rows={3} value={extra.hint} onChange={e => setExtra({ ...extra, hint: e.target.value })} /></label>
                <div className="form-footer"><button className="primary" disabled={busy}>Resume</button><button type="button" className="secondary" onClick={stop} disabled={busy}>Stop</button></div>
              </form>
            </section>
```

- After the checks list, a resume history: `detail.resumes.length > 0 &&` a list showing each resume's extensions and hint, plus a `detail.label` pill reading "assisted" or "extended".
- State and handlers:

```tsx
  const [extra, setExtra] = useState({ turns: 20, tools: 50, minutes: 15, hint: '' });
  const pendingControl = useRef<{ id: string; version: number; kind: string } | null>(null);

  async function control(kind: 'resume' | 'stop') {
    if (!detail || busy) return;
    setBusy(true);
    try {
      const same = pendingControl.current?.version === detail.version && pendingControl.current.kind === kind;
      const commandId = same ? pendingControl.current!.id : freshId();
      pendingControl.current = { id: commandId, version: detail.version, kind };
      if (kind === 'resume') await api.resume(detail.workId, { commandId, expectedVersion: detail.version, extraTurns: extra.turns, extraToolCalls: extra.tools, extraMinutes: extra.minutes, ...(extra.hint.trim() ? { hint: extra.hint.trim() } : {}) });
      else await api.stop(detail.workId, commandId, detail.version);
      pendingControl.current = null;
      setExtra({ turns: 20, tools: 50, minutes: 15, hint: '' });
      await refresh(detail.workId);
    } catch (failure) { setError(`${kind === 'resume' ? 'Resume' : 'Stop'} failed: ${String(failure)}`); }
    finally { setBusy(false); }
  }
  const resume = (event: React.FormEvent) => { event.preventDefault(); void control('resume'); };
  const stop = () => void control('stop');
```

- [ ] **Step 3: Styles**

In `styles.css`, add:

```css
.paused-banner { display: flex; flex-wrap: wrap; gap: .5rem; align-items: center; }
.paused-banner .link { background: none; border: none; text-decoration: underline; cursor: pointer; color: inherit; font: inherit; padding: 0; }
.pause-panel { border: 1px solid currentColor; border-radius: 6px; padding: .75rem; margin: .75rem 0; }
.pause-panel form { display: grid; gap: .5rem; }
```

- [ ] **Step 4: Build the console**

Run: `cd web && npm run build`
Expected: the TypeScript check and Vite build succeed with no type errors.

- [ ] **Step 5: Commit**

```bash
git add web/src
git commit -m "feat: show paused runs with evidence and resume or stop controls in the console"
```

---

### Task 8: Documentation, full build, and acceptance

**Files:**
- Modify: `docs/api.md`, `docs/development.md`, `docs/STATUS.md`, `docs/evidence/local-task-tracker.md`

- [ ] **Step 1: Document the routes and behavior**

`docs/api.md`, HTTP contract table, add rows:

| `POST /api/runs/{id}/resume` | Accept `{commandId, expectedVersion, extraTurns?, extraToolCalls?, extraMinutes?, hint?}` for a `PAUSED` run; `202`; `409` if not paused or stale version; `400` above a ceiling. |
| `POST /api/runs/{id}/stop` | Accept `{commandId, expectedVersion}` for a `PAUSED` run; ends it with its pause reason. |
| `POST /api/work/{id}/resume`, `/stop` | Same for repository work items. |

Also add a paragraph under the walkthrough:
- Pause reasons: `STALLED`, `TURN_LIMIT`, `TOOL_LIMIT`, `TIME_LIMIT`.
- Event types: `NUDGED`, `PAUSED`, `RESUMED`, `STOPPED`.
- The `assisted` and `extended` labels.
- CLI examples:
  - `python3 scripts/harness.py resume RUN --expected-version N --turns 20 --hint "…"`
  - `python3 scripts/harness.py stop RUN --expected-version N`

`docs/development.md`: one paragraph in the Task 7 / working-memory section:
- stall thresholds and ladder;
- the 7-day waiting limit;
- active time excludes paused time;
- the 40-day workflow execution timeout.

- [ ] **Step 2: Full build**

With services up per `docs/development.md` (project `harness-tryout`, `HARNESS_RUNNER_IMAGE` and `HARNESS_REPOSITORY_RUNNER_IMAGE` exported):
Run: `./gradlew build > build.log 2>&1; tail -3 build.log`
Expected: `BUILD SUCCESSFUL`. Read the unit and integration totals from `build/test-results`, and record the commit and the counts.

- [ ] **Step 3: Live acceptance run (spec Acceptance 2)**

Package, start the three roles against a fresh schema `m1_pause_run1` and namespace `m1-pause1` (as for working-memory runs), probe `qwen3.5:9b`, then start a benchmark run.
Expected: it pauses with `STALLED` or a limit reason.
- Open the console. Check that:
  - the banner shows the run;
  - the benchmark link shows `pause` with evidence;
  - after a page reload, the banner and pause data are still there (Review Focus 5).
- Resume once from the CLI or API with extra turns and **no hint** (`--turns 20`).
- Record the outcome as observed: submitted with its evaluator verdict, or paused again.

Label the run **extended**.

- [ ] **Step 4: Scripted repository task (spec Acceptance 3)**

In the console:
1. Submit a repository work item with a profile whose model hits the 1-turn limit. Use a scripted-model setup if one is configured; otherwise use the live model with `extraTurns` granted after the first pause.
2. Confirm the Paused node, the panel, and Resume.
3. Confirm the item reaches "Ready for review".

Record what was done manually, including the browser and steps.

- [ ] **Step 5: Evidence and status**

- Append an "M1 acceptance" section to `docs/evidence/local-task-tracker.md`: commit, JAR SHA-256, schema and namespace, run ID, pause reason and evidence, resume, final outcome and label, and the manual UI check.
- Update `docs/STATUS.md`: M1 status and the next milestone (M2).
- Stop the Java roles and `docker compose -p harness-tryout down`, keeping the volume.

- [ ] **Step 6: Commit**

```bash
git add docs
git commit -m "docs: document pause and resume and record M1 acceptance"
```

## Test growth summary

- New test methods: **1**, `CodingWorkflowTest.turnLimitPausesThenResumesWithExtraTurnsAndHintThenSubmits`.
- Extended:
  - `WorkingMemoryTest.longSessionStaysWithinBudgetAndCarriesTheHandOff`
  - `RepositoryTaskIT` (working-memory assertions)
  - `WorkApiIT.admissionWaitsForOlderDeliveredWork`
  - `RunApiIT.duplicateStartOrderedEventsAndVersionedCancel`
  - `CodingWorkflowTest.secondTextOnlyReplyStallsAndEndsWhenNobodyResumes` (renamed)
  - `CodingWorkflowTest.lateModelResultCannotDispatchToolsAfterTheRunDeadline`
- No crash, race, or deployment-continuity tests.
