# Working-Memory Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the model-visible conversation inside the frozen profile's context budget by folding completed turns into a durable working memory, then iterate live local runs until the host-owned evaluator passes `task-tracker-v1`.

**Architecture:** A pure `execution/WorkingMemory` class defines conversation format v2 (task, structured hand-off state, last K turns, compacted trace), folds each turn, and enforces the budget. `RunActivitiesImpl` writes v2 conversations and, at call time, renders v2 into the existing v1 message shape for the unchanged adapter. `ToolRouter` returns file hashes, extracted compiler errors, and actionable error details. `CodingWorkflowImpl`, activity signatures, migrations, and the API are unchanged.

**Tech Stack:** Java 21, Spring Boot, Temporal Java SDK, Jackson 3 (`tools.jackson`), JUnit 5, Gradle wrapper, local Ollama.

**Spec:** [docs/superpowers/specs/2026-10-17-working-memory-context-design.md](../specs/2026-10-17-working-memory-context-design.md)

## Global Constraints

- K = 2 recent turns; at most 20 error lines; 5 most recent rejections; note at most 1.5 KB (700 characters each for `progress` and `next`).
- Budget: `maxInputTokens = (contextTokens - outputTokens) * 0.9`; tokens estimated as rendered JSON characters / 3.
- Budget enforcement order: reduce K to 1, elide `write_file` contents in recent turns, shorten `read_file` results in recent turns, else `MODEL_CONTEXT_OVERFLOW` → `CONTEXT_OVERFLOW`.
- Check results keep a 4 KiB `logTail`; the full log stays in the runner receipt log blob.
- Prompts use only public material (`REQUIREMENTS.md` and tool definitions). Never copy evaluator fixtures, private cases, or expected answers into prompts, the starter, or the runner image.
- Fewest tests possible: extend existing tests first. Net new test methods for this plan: **2** (both in `WorkingMemoryTest`). No crash, race, recovery, or deployment tests.
- Do not change `CodingWorkflowImpl`, `RunActivities` signatures, or Flyway migrations.
- Live runs: no model downloads without owner approval; stop and report after 3 consecutive failed live runs; scripted or repository-task completion never counts as benchmark acceptance.
- Follow [AGENTS.md](../../../AGENTS.md) for Git, PR, secrets, and resource cleanup. Never merge to `main`.

## Deviations from the spec (decided while planning)

1. **Rendering lives in `RunActivitiesImpl.callModel`, not `OllamaModelAdapter`.** `execution` depends on `model`, not the reverse, so the adapter cannot use `WorkingMemory` types. `callModel` renders v2 into a v1-shaped blob (content-addressed, so it is also evidence of exactly what was sent) and passes the adapter a `ModelCall` naming that blob. The durable request receipt still hashes the workflow's original `ModelCall`, so redelivery identity is unchanged. The adapter and `OllamaAdapterTest` stay untouched; v2 rendering is asserted in `WorkingMemoryTest`.
2. **Overflow is detected in `callModel`, not thrown from `appendConversation`.** An `appendConversation` failure is not caught by the workflow and would fail the workflow without releasing admission. `fold` therefore always returns its best effort, and `callModel` raises the existing `MODEL_CONTEXT_OVERFLOW` failure, which the workflow already maps to `CONTEXT_OVERFLOW`.
3. **The end-to-end budget assertion goes in `RepositoryTaskIT`, not `CodingWorkflowSmokeIT`.** The smoke test stubs conversation activities entirely; `RepositoryTaskIT` drives the real `RunActivitiesImpl`.
4. **The v1 continuation path is kept verbatim and not newly tested.** Its code does not change.

## Review Focus

1. Model `write_file` arguments that are not valid JSON must not break folding; the turn is kept as recorded and the rejection is carried in `state.rejected`. Covered in Task 1's long-session test (turn 7). `elideWrites` and `update` share the same `parse` helper, which returns `null` on malformed JSON.
2. A `read_file` result for a file the run later writes must keep `written=true` and the newest hash. Covered in Task 1 (README.md is read after being written).
3. A reply with no tool call (reminder turn) must still advance `state.turn` so compacted invocation IDs match workflow turn numbers. Covered in Task 1 (turn 13).
4. A `write_file` with a stale `expectedSha256` must return the current hash in `detail` without writing. Covered in Task 2's updated write test.
5. A check log with no recognizable error lines must return `errors: []` and still return `logTail`. Covered in Task 2's run-tests assertions (the "Compilation failed at line 12" line alone does not match).

## File structure

- Create `src/main/java/com/brianmehrman/harness/execution/WorkingMemory.java`: v2 records, `start`, `fold`, `render`, `fits`, `estimateTokens`, `read`, `write`, `isV2`, `errorLines`. Pure; no Spring, no I/O.
- Create `src/test/java/com/brianmehrman/harness/execution/WorkingMemoryTest.java`: 2 test methods.
- Modify `src/main/java/com/brianmehrman/harness/execution/ToolRouter.java`: write/read/run result shapes and error details.
- Modify `src/test/java/com/brianmehrman/harness/execution/ToolRouterTest.java`: extend existing write, read, and run-tests tests.
- Modify `src/main/java/com/brianmehrman/harness/execution/RunActivitiesImpl.java`: v2 start and prompts, v2 fold, render at call time, overflow check.
- Modify `src/test/java/com/brianmehrman/harness/execution/RepositoryTaskIT.java`: assert every recorded model call used an in-budget v2 conversation.
- Modify `docs/evidence/local-task-tracker.md`, `docs/development.md` (Task 7 section note), `docs/STATUS.md`.

---

### Task 1: WorkingMemory (pure fold, budget, and rendering)

**Files:**
- Create: `src/main/java/com/brianmehrman/harness/execution/WorkingMemory.java`
- Test: `src/test/java/com/brianmehrman/harness/execution/WorkingMemoryTest.java`

**Interfaces:**
- Consumes: `ModelReply(String text, List<ToolRequest> tools, Long inputTokens, Long outputTokens, String finishReason)`, `ToolRequest(String providerCallId, String name, String argumentsJson)`, `ToolOutcome(String invocationId, String providerCallId, String name, SnapshotRef snapshot, String responseJson, boolean attemptedSubmission, boolean passed)`, `SnapshotHasher.content(String)`.
- Produces (used by Tasks 2 and 3):
  - `WorkingMemory.Conversation start(String runId, String system, String task, int contextTokens, int outputTokens)`
  - `WorkingMemory.Conversation fold(Conversation previous, ModelReply reply, List<ToolOutcome> outcomes)` — throws `IllegalArgumentException` on tool result count/identity mismatch, like today's `appendConversation`
  - `Map<String, Object> render(Conversation)` — `{"version":1,"messages":[...]}` in the shape `OllamaModelAdapter` already parses
  - `boolean fits(Conversation)`, `int estimateTokens(Conversation)`
  - `byte[] write(Conversation)`, `Conversation read(byte[])`, `boolean isV2(byte[])`
  - `List<String> errorLines(String log)` — at most 20 matching lines, each at most 300 characters
  - Records: `Conversation(int version, String runId, Budget budget, String system, String task, State state, List<Turn> recent, Compacted compacted)`, `Budget(int contextTokens, int outputTokens, int maxInputTokens)`, `State(int turn, List<FileState> files, CheckState lastCheck, List<Rejection> rejected, Note note)`, `FileState(String path, String sha256, boolean written, int turn)`, `CheckState(String tool, String outcome, Integer exitCode, List<String> errors, String receiptRef)`, `Rejection(int turn, String tool, String code, String detail)`, `Note(int turn, String progress, String next)`, `Turn(int turn, List<Map<String, Object>> messages)`, `Compacted(int turns, List<String> modelAttempts)`
  - Tool result JSON fields read by `fold` (produced by Task 2): `write_file` → `status, path, sha256`; `read_file` → `status, sha256` (path from the request arguments); `build`/`run_tests` → `status, exitCode, errors`; `submit` → `status, failedCases`; any error → `status:"ERROR", code, detail`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/brianmehrman/harness/execution/WorkingMemoryTest.java`:

```java
package com.brianmehrman.harness.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.model.ModelReply;
import com.brianmehrman.harness.model.ToolRequest;
import com.brianmehrman.harness.workspace.SnapshotHasher;
import com.brianmehrman.harness.workspace.SnapshotRef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WorkingMemoryTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SnapshotRef SNAPSHOT = new SnapshotRef("a".repeat(64));

    @Test void longSessionStaysWithinBudgetAndCarriesTheHandOff() {
        var memory = WorkingMemory.start("run-1", "system rules", "task text", 4096, 512);
        assertEquals(3225, memory.budget().maxInputTokens());
        String lastWrite = null;
        for (int turn = 1; turn <= 20; turn++) {
            String id = "run-1:turn-" + turn + ":tool-1";
            ModelReply reply;
            List<ToolOutcome> outcomes;
            String note = "Progress: step " + turn + "\nNext: build";
            switch (turn) {
                case 7 -> { // malformed model arguments must not break folding or elision
                    reply = new ModelReply(note, List.of(new ToolRequest("p", "write_file", "{not json")), 1L, 1L, "stop");
                    outcomes = List.of(outcome(id, "write_file", Map.of("status", "ERROR", "code", "INVALID_ARGUMENTS",
                            "detail", "arguments are not a JSON object")));
                }
                case 10 -> {
                    reply = new ModelReply(note, List.of(new ToolRequest("p", "build", "{}")), 1L, 1L, "stop");
                    outcomes = List.of(outcome(id, "build", Map.of("status", "FAILED", "exitCode", 1,
                            "errors", List.of("Main.java:3: error: cannot find symbol"), "logTail", "...")));
                }
                case 11 -> {
                    reply = new ModelReply(note, List.of(new ToolRequest("p", "write_file",
                            write("Main.java", "0".repeat(64), "y".repeat(12_000)))), 1L, 1L, "stop");
                    outcomes = List.of(outcome(id, "write_file", Map.of("status", "ERROR", "code", "INVALID_ARGUMENTS",
                            "detail", "expectedSha256 does not match Main.java; current sha256 is " + "b".repeat(64))));
                }
                case 12 -> {
                    String readme = "r".repeat(20_000);
                    reply = new ModelReply(note, List.of(new ToolRequest("p", "read_file", "{\"path\":\"README.md\"}")), 1L, 1L, "stop");
                    outcomes = List.of(outcome(id, "read_file", Map.of("status", "OK", "content", readme,
                            "sha256", SnapshotHasher.content(readme))));
                }
                case 13 -> { // no tool call: reminder turn still advances the turn counter
                    reply = new ModelReply("thinking only", List.of(), 1L, 1L, "length");
                    outcomes = List.of();
                }
                default -> {
                    String path = turn == 5 ? "README.md" : "Main.java";
                    String content = "x".repeat(12_000) + turn;
                    if (path.equals("Main.java")) lastWrite = content;
                    reply = new ModelReply(note, List.of(new ToolRequest("p", "write_file",
                            write(path, "absent", content))), 1L, 1L, "stop");
                    outcomes = List.of(outcome(id, "write_file", Map.of("status", "OK", "path", path,
                            "sha256", SnapshotHasher.content(content), "snapshotSha256", "c".repeat(64))));
                }
            }
            var next = WorkingMemory.fold(memory, reply, outcomes);
            assertArrayEquals(WorkingMemory.write(next), WorkingMemory.write(WorkingMemory.fold(memory, reply, outcomes)),
                    "Folding is deterministic");
            assertEquals(next, WorkingMemory.read(WorkingMemory.write(next)), "v2 round-trips");
            assertTrue(WorkingMemory.fits(next), "turn " + turn + " exceeds the budget");
            if (turn == 12) assertTrue(lastMessage(next).get("content").toString().contains("chars elided"));
            if (turn == 13) {
                var reminder = lastMessage(next);
                assertEquals("user", reminder.get("role"));
                assertTrue(reminder.get("content").toString().contains("Your last Next: build"));
                assertTrue(reminder.get("content").toString().contains("Main.java:3: error: cannot find symbol"));
            }
            memory = next;
        }

        var state = memory.state();
        assertEquals(20, state.turn());
        assertEquals(20, memory.compacted().turns() + memory.recent().size());
        assertEquals("run-1:turn-1", memory.compacted().modelAttempts().getFirst());
        assertEquals(new WorkingMemory.FileState("Main.java", SnapshotHasher.content(lastWrite), true, 20),
                state.files().stream().filter(f -> f.path().equals("Main.java")).findFirst().orElseThrow());
        var readme = state.files().stream().filter(f -> f.path().equals("README.md")).findFirst().orElseThrow();
        assertTrue(readme.written(), "A later read keeps the written flag");
        assertEquals(12, readme.turn());
        assertEquals(List.of("Main.java:3: error: cannot find symbol"), state.lastCheck().errors());
        assertEquals("run-1:turn-10:tool-1", state.lastCheck().receiptRef());
        assertTrue(state.rejected().stream().anyMatch(r -> r.detail().contains("current sha256 is")));
        assertEquals(new WorkingMemory.Note(20, "step 20", "build"), state.note());
        assertTrue(memory.recent().getLast().messages().getFirst().get("toolCalls").toString().contains("[written: Main.java"));

        var messages = (List<?>) WorkingMemory.render(memory).get("messages");
        assertEquals("system", ((Map<?, ?>) messages.get(0)).get("role"));
        assertEquals("task text", ((Map<?, ?>) messages.get(1)).get("content"));
        assertTrue(((Map<?, ?>) messages.get(2)).get("content").toString().startsWith("Working state after turn 20"));
        assertEquals("assistant", ((Map<?, ?>) messages.get(3)).get("role"));
        assertEquals("tool", ((Map<?, ?>) messages.get(4)).get("role"));
    }

    @Test void taskLargerThanTheBudgetIsReportedInsteadOfCut() {
        var memory = WorkingMemory.start("run-1", "system rules", "t".repeat(20_000), 4096, 512);
        assertFalse(WorkingMemory.fits(memory));
        var next = WorkingMemory.fold(memory, new ModelReply("", List.of(), 1L, 1L, "stop"), List.of());
        assertFalse(WorkingMemory.fits(next));
        assertEquals("t".repeat(20_000), next.task(), "The task is never compacted");
        assertEquals(List.of(), WorkingMemory.errorLines("Compilation failed at line 12"));
    }

    private static ToolOutcome outcome(String id, String name, Map<String, ?> response) {
        return new ToolOutcome(id, "p", name, SNAPSHOT, JSON.writeValueAsString(response), false, false);
    }

    private static String write(String path, String expected, String content) {
        return JSON.writeValueAsString(Map.of("path", path, "expectedSha256", expected, "content", content));
    }

    private static Map<String, Object> lastMessage(WorkingMemory.Conversation memory) {
        return memory.recent().getLast().messages().getLast();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.brianmehrman.harness.execution.WorkingMemoryTest'`
Expected: compilation FAIL, `cannot find symbol: class WorkingMemory`.

- [ ] **Step 3: Implement `WorkingMemory`**

Create `src/main/java/com/brianmehrman/harness/execution/WorkingMemory.java`:

```java
package com.brianmehrman.harness.execution;

import com.brianmehrman.harness.model.ModelReply;
import com.brianmehrman.harness.workspace.SnapshotHasher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure fold of completed turns into the bounded, model-visible conversation (format v2). */
public final class WorkingMemory {
    static final int RECENT_TURNS = 2;
    static final int ERROR_LINES = 20;
    static final int REJECTED_KEPT = 5;
    static final int NOTE_FIELD_CHARS = 700;
    static final int READ_KEEP_CHARS = 1000;
    private static final int CHARS_PER_TOKEN = 3;
    private static final Pattern ERROR = Pattern.compile("\\.java:\\d+: error:|\\bFAILED\\b|AssertionFailedError");
    private static final Pattern PROGRESS = Pattern.compile("(?im)^\\s*Progress:\\s*(.+)$");
    private static final Pattern NEXT = Pattern.compile("(?im)^\\s*Next:\\s*(.+)$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Conversation(int version, String runId, Budget budget, String system, String task,
            State state, List<Turn> recent, Compacted compacted) {}
    public record Budget(int contextTokens, int outputTokens, int maxInputTokens) {}
    public record State(int turn, List<FileState> files, CheckState lastCheck, List<Rejection> rejected, Note note) {}
    public record FileState(String path, String sha256, boolean written, int turn) {}
    public record CheckState(String tool, String outcome, Integer exitCode, List<String> errors, String receiptRef) {}
    public record Rejection(int turn, String tool, String code, String detail) {}
    public record Note(int turn, String progress, String next) {}
    public record Turn(int turn, List<Map<String, Object>> messages) {}
    public record Compacted(int turns, List<String> modelAttempts) {}

    private WorkingMemory() {}

    public static Conversation start(String runId, String system, String task, int contextTokens, int outputTokens) {
        int max = (int) ((contextTokens - outputTokens) * 0.9);
        return new Conversation(2, runId, new Budget(contextTokens, outputTokens, max), system, task,
                new State(0, List.of(), null, List.of(), null), List.of(), new Compacted(0, List.of()));
    }

    public static Conversation fold(Conversation previous, ModelReply reply, List<ToolOutcome> outcomes) {
        int turn = previous.state().turn() + 1;
        State state = update(previous.state(), turn, reply, outcomes);
        var turns = new ArrayList<>(previous.recent());
        turns.add(new Turn(turn, messages(reply, outcomes, state)));
        Conversation next = keep(previous, state, turns, RECENT_TURNS);
        if (fits(next)) return next;
        next = keep(previous, state, turns, 1);
        if (fits(next)) return next;
        next = mapRecent(next, WorkingMemory::elideWrites);
        if (fits(next)) return next;
        return mapRecent(next, WorkingMemory::shortenReads); // callModel reports overflow if this still does not fit
    }

    public static Map<String, Object> render(Conversation memory) {
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(message("system", memory.system()));
        messages.add(message("user", memory.task()));
        if (memory.state().turn() > 0) messages.add(message("user", stateText(memory)));
        memory.recent().forEach(turn -> messages.addAll(turn.messages()));
        var root = new LinkedHashMap<String, Object>();
        root.put("version", 1);
        root.put("messages", messages);
        return root;
    }

    public static int estimateTokens(Conversation memory) {
        return (JSON.writeValueAsString(render(memory)).length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    public static boolean fits(Conversation memory) {
        return estimateTokens(memory) <= memory.budget().maxInputTokens();
    }

    public static byte[] write(Conversation memory) { return JSON.writeValueAsBytes(memory); }

    public static Conversation read(byte[] bytes) { return JSON.readValue(bytes, Conversation.class); }

    public static boolean isV2(byte[] bytes) { return JSON.readTree(bytes).path("version").asInt() == 2; }

    public static List<String> errorLines(String log) {
        if (log == null) return List.of();
        return log.lines().filter(line -> ERROR.matcher(line).find())
                .map(line -> cap(line.strip(), 300)).limit(ERROR_LINES).toList();
    }

    private static State update(State previous, int turn, ModelReply reply, List<ToolOutcome> outcomes) {
        var files = new TreeMap<String, FileState>();
        previous.files().forEach(file -> files.put(file.path(), file));
        CheckState check = previous.lastCheck();
        var rejected = new ArrayList<>(previous.rejected());
        for (int i = 0; i < outcomes.size() && i < reply.tools().size(); i++) {
            ToolOutcome outcome = outcomes.get(i);
            JsonNode result = parse(outcome.responseJson());
            if (result == null) continue;
            if ("ERROR".equals(result.path("status").asText(""))) {
                rejected.add(new Rejection(turn, outcome.name(), result.path("code").asText(""), text(result, "detail")));
                continue;
            }
            switch (outcome.name()) {
                case "read_file" -> {
                    JsonNode args = parse(reply.tools().get(i).argumentsJson());
                    String path = args == null ? null : text(args, "path");
                    FileState old = path == null ? null : files.get(path);
                    if (path != null) files.put(path, new FileState(path, result.path("sha256").asText(""),
                            old != null && old.written(), turn));
                }
                case "write_file" -> {
                    String path = text(result, "path");
                    if (path != null) files.put(path, new FileState(path, result.path("sha256").asText(""), true, turn));
                }
                case "build", "run_tests" -> check = new CheckState(outcome.name(), result.path("status").asText(""),
                        result.path("exitCode").isNumber() ? result.path("exitCode").asInt() : null,
                        strings(result.path("errors")), outcome.invocationId());
                case "submit" -> check = new CheckState("submit", result.path("status").asText(""), null,
                        strings(result.path("failedCases")), outcome.invocationId());
                default -> { }
            }
        }
        while (rejected.size() > REJECTED_KEPT) rejected.removeFirst();
        Note note = note(turn, reply.text());
        return new State(turn, List.copyOf(files.values()), check, List.copyOf(rejected),
                note == null ? previous.note() : note);
    }

    private static List<Map<String, Object>> messages(ModelReply reply, List<ToolOutcome> outcomes, State state) {
        var messages = new ArrayList<Map<String, Object>>();
        var calls = new ArrayList<Map<String, Object>>();
        for (var request : reply.tools()) {
            var call = new LinkedHashMap<String, Object>();
            call.put("id", request.providerCallId());
            call.put("name", request.name());
            call.put("argumentsJson", request.argumentsJson());
            calls.add(call);
        }
        var assistant = new LinkedHashMap<String, Object>();
        assistant.put("role", "assistant");
        assistant.put("content", reply.text() == null ? "" : reply.text());
        assistant.put("toolCalls", calls);
        messages.add(assistant);
        if (outcomes.size() != calls.size()) throw new IllegalArgumentException("Tool result count mismatch");
        for (int i = 0; i < outcomes.size(); i++) {
            var outcome = outcomes.get(i);
            var request = reply.tools().get(i);
            if (!request.providerCallId().equals(outcome.providerCallId()) || !request.name().equals(outcome.name()))
                throw new IllegalArgumentException("Tool result does not match request");
            var tool = new LinkedHashMap<String, Object>();
            tool.put("role", "tool");
            tool.put("id", outcome.providerCallId());
            tool.put("name", outcome.name());
            tool.put("content", outcome.responseJson());
            messages.add(tool);
        }
        if (outcomes.isEmpty()) messages.add(message("user", reminder(state)));
        return messages;
    }

    private static String reminder(State state) {
        var text = new StringBuilder("Use the provided tools and submit the immutable snapshot. Text alone cannot complete this task.");
        if (state.note() != null && state.note().next() != null)
            text.append("\nYour last Next: ").append(state.note().next());
        if (state.lastCheck() != null && !state.lastCheck().errors().isEmpty()) {
            text.append("\nLast ").append(state.lastCheck().tool()).append(" errors:");
            state.lastCheck().errors().stream().limit(5).forEach(error -> text.append('\n').append(error));
        }
        return text.toString();
    }

    private static String stateText(Conversation memory) {
        State state = memory.state();
        var text = new StringBuilder("Working state after turn ").append(state.turn()).append(" (")
                .append(memory.compacted().turns())
                .append(" earlier turns compacted). This note is authoritative; reread files when you need their contents.");
        if (!state.files().isEmpty()) {
            text.append("\nFiles you read or wrote:");
            for (var file : state.files())
                text.append("\n- ").append(file.path()).append(" sha256=").append(file.sha256())
                        .append(file.written() ? " (written" : " (read").append(", turn ").append(file.turn()).append(')');
        }
        if (state.lastCheck() != null) {
            var check = state.lastCheck();
            text.append("\nLast check: ").append(check.tool()).append(' ').append(check.outcome());
            if (check.exitCode() != null) text.append(" exit ").append(check.exitCode());
            check.errors().forEach(error -> text.append("\n  ").append(error));
        }
        if (!state.rejected().isEmpty()) {
            text.append("\nRejected tool calls:");
            for (var rejection : state.rejected()) {
                text.append("\n- turn ").append(rejection.turn()).append(' ').append(rejection.tool())
                        .append(' ').append(rejection.code());
                if (rejection.detail() != null) text.append(": ").append(rejection.detail());
            }
        }
        if (state.note() != null) {
            text.append("\nYour note from turn ").append(state.note().turn()).append(':');
            if (state.note().progress() != null) text.append("\nProgress: ").append(state.note().progress());
            if (state.note().next() != null) text.append("\nNext: ").append(state.note().next());
        }
        return text.toString();
    }

    private static Conversation keep(Conversation base, State state, List<Turn> turns, int keep) {
        var attempts = new ArrayList<>(base.compacted().modelAttempts());
        int folded = base.compacted().turns();
        var kept = new ArrayList<>(turns);
        while (kept.size() > keep) {
            attempts.add(base.runId() + ":turn-" + kept.removeFirst().turn());
            folded++;
        }
        return new Conversation(2, base.runId(), base.budget(), base.system(), base.task(), state,
                List.copyOf(kept), new Compacted(folded, List.copyOf(attempts)));
    }

    private static Conversation mapRecent(Conversation memory, UnaryOperator<Map<String, Object>> change) {
        var turns = memory.recent().stream()
                .map(turn -> new Turn(turn.turn(), turn.messages().stream().map(change).toList())).toList();
        return new Conversation(2, memory.runId(), memory.budget(), memory.system(), memory.task(),
                memory.state(), turns, memory.compacted());
    }

    private static Map<String, Object> elideWrites(Map<String, Object> message) {
        if (!(message.get("toolCalls") instanceof List<?> calls)) return message;
        var changed = new ArrayList<Map<String, Object>>();
        for (Object item : calls) {
            @SuppressWarnings("unchecked")
            var call = new LinkedHashMap<>((Map<String, Object>) item);
            JsonNode args = "write_file".equals(call.get("name")) ? parse((String) call.get("argumentsJson")) : null;
            String content = args == null ? null : text(args, "content");
            if (content != null && !content.startsWith("[written: ")) {
                var shortened = new LinkedHashMap<String, Object>();
                shortened.put("path", args.path("path").asText(""));
                shortened.put("expectedSha256", args.path("expectedSha256").asText(""));
                shortened.put("content", "[written: " + args.path("path").asText("") + ", sha256 " +
                        SnapshotHasher.content(content) + ", " + content.length() + " chars]");
                call.put("argumentsJson", JSON.writeValueAsString(shortened));
            }
            changed.add(call);
        }
        var copy = new LinkedHashMap<>(message);
        copy.put("toolCalls", changed);
        return copy;
    }

    private static Map<String, Object> shortenReads(Map<String, Object> message) {
        if (!"tool".equals(message.get("role")) || !"read_file".equals(message.get("name"))) return message;
        JsonNode result = parse((String) message.get("content"));
        String content = result == null ? null : text(result, "content");
        if (content == null || content.length() <= 2 * READ_KEEP_CHARS) return message;
        var shortened = new LinkedHashMap<String, Object>();
        shortened.put("status", result.path("status").asText(""));
        shortened.put("sha256", result.path("sha256").asText(""));
        shortened.put("content", content.substring(0, READ_KEEP_CHARS) + "\n[... " +
                (content.length() - 2 * READ_KEEP_CHARS) + " chars elided; read_file again if needed ...]\n" +
                content.substring(content.length() - READ_KEEP_CHARS));
        var copy = new LinkedHashMap<>(message);
        copy.put("content", JSON.writeValueAsString(shortened));
        return copy;
    }

    private static Note note(int turn, String text) {
        if (text == null) return null;
        String progress = last(PROGRESS, text);
        String next = last(NEXT, text);
        return progress == null && next == null ? null : new Note(turn, progress, next);
    }

    private static String last(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        String found = null;
        while (matcher.find()) found = cap(matcher.group(1).strip(), NOTE_FIELD_CHARS);
        return found;
    }

    private static List<String> strings(JsonNode array) {
        var values = new ArrayList<String>();
        if (array.isArray()) for (JsonNode item : array) {
            if (values.size() == ERROR_LINES) break;
            values.add(cap(item.asText(""), 300));
        }
        return List.copyOf(values);
    }

    private static JsonNode parse(String json) {
        if (json == null) return null;
        try {
            JsonNode node = JSON.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (JacksonException malformed) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).isTextual() ? node.path(field).asText() : null;
    }

    private static String cap(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static Map<String, Object> message(String role, String content) {
        var message = new LinkedHashMap<String, Object>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.brianmehrman.harness.execution.WorkingMemoryTest'`
Expected: PASS, 2 tests. If `assertEquals(next, read(write(next)))` fails because Jackson deserializes numbers in message maps differently (for example `Integer` vs `Long`), compare `write(read(write(next)))` bytes to `write(next)` instead; the requirement is byte-stable round trips.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/brianmehrman/harness/execution/WorkingMemory.java src/test/java/com/brianmehrman/harness/execution/WorkingMemoryTest.java
git commit -m "feat: fold coding turns into a bounded working memory"
```

---

### Task 2: Tool result shapes and error details

**Files:**
- Modify: `src/main/java/com/brianmehrman/harness/execution/ToolRouter.java` (`read`, `write`, `run`, `error`)
- Test: `src/test/java/com/brianmehrman/harness/execution/ToolRouterTest.java` (extend existing tests only)

**Interfaces:**
- Consumes: `WorkingMemory.errorLines(String)` from Task 1.
- Produces: the JSON fields listed in Task 1's Interfaces block. `error(call, code, detail)` adds a `detail` string; codes are unchanged.

- [ ] **Step 1: Extend the existing tests (failing)**

In `ToolRouterTest`:

1. Replace `writeUsesHarnessSnapshotAndReturnsNewSnapshot` with a version that writes a new path and checks hash feedback, then a stale-hash write:

```java
    @Test void writeUsesHarnessSnapshotAndReturnsNewSnapshot() {
        var workspace = new MemoryWorkspace();
        var router = new ToolRouter(workspace, new NoRunner(), new NoEvaluator(), new MemoryBlobs());
        var call = new ToolInvocation("run-1", "run-1:turn-1:tool-1", START,
                new ToolRequest("provider-1", "write_file", "{\"path\":\"NOTES.md\",\"expectedSha256\":\"absent\",\"content\":\"hello\"}"),
                System.currentTimeMillis() + 10_000);

        ToolOutcome outcome = router.route(call);

        assertEquals(EDITED, outcome.snapshot());
        assertEquals("provider-1", outcome.providerCallId());
        assertTrue(outcome.responseJson().contains("\"status\":\"OK\""));
        assertTrue(outcome.responseJson().contains("\"path\":\"NOTES.md\""));
        assertTrue(outcome.responseJson().contains("\"sha256\":\"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824\""));
        assertEquals("run-1", workspace.lastWrite.runId());
        assertEquals(START, workspace.lastWrite.parent());
        assertEquals("run-1:turn-1:tool-1", workspace.lastWrite.invocationId());

        workspace.lastWrite = null;
        ToolOutcome stale = router.route(new ToolInvocation("run-1", "run-1:turn-2:tool-1", START,
                new ToolRequest("provider-2", "write_file", "{\"path\":\"README.md\",\"expectedSha256\":\"absent\",\"content\":\"bye\"}"),
                System.currentTimeMillis() + 10_000));
        assertTrue(stale.responseJson().contains("INVALID_ARGUMENTS"));
        assertTrue(stale.responseJson().contains("current sha256 is 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"));
        assertNull(workspace.lastWrite, "A stale hash must not reach the workspace");
    }
```

2. In `listAndReadUseOnlyTheCurrentImmutableSnapshot`, append:

```java
        ToolOutcome missing = router.route(new ToolInvocation("run-1", "read-2", START,
                new ToolRequest("p-read", "read_file", "{\"path\":\"MISSING.md\"}"), System.currentTimeMillis() + 10_000));
        assertTrue(missing.responseJson().contains("FILE_NOT_FOUND"));
        assertTrue(missing.responseJson().contains("MISSING.md"));
```

3. In `runTestsUsesFixedRunnerOperationAndCurrentSnapshot`, append:

```java
        assertTrue(result.responseJson().contains("\"errors\":[\"src/Main.java:12: error: ';' expected\"]"));
        assertTrue(result.responseJson().contains("\"logTail\""));
        assertFalse(result.responseJson().contains("\"log\":"));
```

and change `MemoryBlobs.get` to return `"Compilation failed at line 12\nsrc/Main.java:12: error: ';' expected\n"`. The existing `contains("Compilation failed at line 12")` assertion stays and now checks `logTail`; that line alone is not an error line (Review Focus 5).

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests 'com.brianmehrman.harness.execution.ToolRouterTest'`
Expected: FAIL in the write, read, and run-tests tests on the new assertions.

- [ ] **Step 3: Implement**

In `ToolRouter`:

```java
    private ToolOutcome read(ToolInvocation call, JsonNode args) {
        if (args.size() != 1 || !args.path("path").isTextual()) return error(call, "INVALID_ARGUMENTS");
        String path = args.path("path").asText();
        if (path.startsWith("/") || path.contains("..") || path.contains("\\") ||
                path.codePoints().anyMatch(Character::isISOControl)) return error(call, "INVALID_ARGUMENTS");
        String content = workspaces.files(call.runId(), call.snapshot()).get(path);
        if (content == null) return error(call, "FILE_NOT_FOUND", "No file at " + path + "; use list_files");
        return ok(call, call.snapshot(), Map.of("status", "OK", "content", content,
                "sha256", SnapshotHasher.content(content)), false, false);
    }

    private ToolOutcome write(ToolInvocation call, JsonNode args) {
        if (args.size() != 3 || !args.path("path").isTextual() || !args.path("expectedSha256").isTextual() ||
                !args.path("content").isTextual()) return error(call, "INVALID_ARGUMENTS");
        String path = args.path("path").asText();
        String content = args.path("content").asText();
        String current = workspaces.files(call.runId(), call.snapshot()).get(path);
        String actual = current == null ? "absent" : SnapshotHasher.content(current);
        if (!actual.equals(args.path("expectedSha256").asText()))
            return error(call, "INVALID_ARGUMENTS", "expectedSha256 does not match " + path + "; current sha256 is " + actual);
        var result = workspaces.write(new WriteRequest(call.runId(), call.invocationId(), call.snapshot(),
                path, args.path("expectedSha256").asText(), content));
        return ok(call, result, Map.of("status", "OK", "path", path, "sha256", SnapshotHasher.content(content),
                "snapshotSha256", result.sha256()), false, false);
    }
```

In `run`, replace the `log`/`truncated` lines:

```java
        String log = receipt.logBlobId() == null ? "" : new String(blobs.get(receipt.logBlobId()), StandardCharsets.UTF_8);
        boolean shortened = log.length() > 4096;
        data.put("errors", WorkingMemory.errorLines(log));
        data.put("logTail", shortened ? log.substring(log.length() - 4096) : log);
        data.put("truncated", receipt.truncated() || shortened);
```

Replace `error`:

```java
    private ToolOutcome error(ToolInvocation call, String code) { return error(call, code, null); }

    private ToolOutcome error(ToolInvocation call, String code, String detail) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("status", "ERROR");
        body.put("code", code);
        if (detail != null) body.put("detail", detail);
        return new ToolOutcome(call.invocationId(), call.request().providerCallId(), call.request().name(),
                call.snapshot(), json.writeValueAsString(body), false, false);
    }
```

In `route`'s catch block, keep `INVALID_ARGUMENTS` but add a detail: `return error(call, "INVALID_ARGUMENTS", "arguments are not a valid JSON object for " + name);`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'com.brianmehrman.harness.execution.ToolRouterTest' --tests 'com.brianmehrman.harness.execution.WorkingMemoryTest'`
Expected: PASS (9 tests: 7 router, 2 working memory).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/brianmehrman/harness/execution/ToolRouter.java src/test/java/com/brianmehrman/harness/execution/ToolRouterTest.java
git commit -m "feat: return file hashes, check errors, and error details to the model"
```

---

### Task 3: Wire working memory into the activities and prompts

**Files:**
- Modify: `src/main/java/com/brianmehrman/harness/execution/RunActivitiesImpl.java` (`startConversation`, `callModel`, `appendConversation`, `conversationBlob`)
- Test: `src/test/java/com/brianmehrman/harness/execution/RepositoryTaskIT.java` (extend the existing test)

**Interfaces:**
- Consumes: `WorkingMemory.start/fold/render/fits/read/write/isV2` (Task 1); `ProfileRevisionStore.get(String)` returning `ProfileRevision` with `contextTokens()` and `outputTokens()`; `RunSpec.profileRevision()`, `RunSpec.runId()`.
- Produces: v2 conversation blobs for every new run; rendered v1-shaped blobs passed to adapters; `MODEL_CONTEXT_OVERFLOW` `ApplicationFailure` when a v2 conversation cannot fit.

- [ ] **Step 1: Extend `RepositoryTaskIT` (failing)**

`WorkingMemory` is in the same package, so no import is needed. After the existing `assertEquals("review", events.getLast().path("stage").asText());` line, add:

```java
            var requests = jdbc.queryForList("select request_blob_id from model_attempt where run_id=? " +
                    "order by invocation_id, attempt", String.class, workId);
            assertEquals(4, requests.size());
            WorkingMemory.Conversation lastSeen = null;
            for (String request : requests) {
                String conversation = json.readTree(blobs.get(request)).path("conversationBlobId").asText();
                lastSeen = WorkingMemory.read(blobs.get(conversation));
                assertEquals(2, lastSeen.version());
                assertTrue(WorkingMemory.fits(lastSeen), "Every model call must stay within the frozen budget");
            }
            assertEquals(3, lastSeen.state().turn());
            assertTrue(lastSeen.state().files().stream().anyMatch(f -> f.path().equals(PROBE) && f.written()));
            assertEquals("build", lastSeen.state().lastCheck().tool());
            assertTrue(lastSeen.system().contains("Progress:"), "The protocol rules reach repository runs");
```

- [ ] **Step 2: Run the integration test to verify it fails**

Prerequisites per [docs/development.md](../../development.md): PostgreSQL and Temporal running, Docker available, `HARNESS_REPOSITORY_RUNNER_IMAGE` set from `runner/repository-image.lock`.

Run: `./gradlew integrationTest --tests 'com.brianmehrman.harness.execution.RepositoryTaskIT'`
Expected: FAIL, because conversation blobs are still v1 (`version` 1, Jackson cannot map `messages` to `Conversation`, or the version assertion fails).

- [ ] **Step 3: Implement in `RunActivitiesImpl`**

Add constants:

```java
    private static final String CONVERSATION = "application/vnd.harness.conversation+json";
    private static final String PROTOCOL = """

            Protocol:
            - Every reply must call at least one tool.
            - Read a file before overwriting it, and pass the sha256 from your latest read_file or write_file result as expectedSha256 ("absent" for a new file).
            - After writing, run build and fix the reported errors, then run run_tests; submit only after both pass.
            - End each reply's text with two short lines: "Progress: <what is done>" and "Next: <your next step>".
            - Older turns are compacted into a "Working state" message. Treat it as authoritative and reread files when you need their contents.""";
```

Replace `startConversation`:

```java
    @Override public String startConversation(RunSpec spec) {
        var profile = profiles.get(spec.profileRevision());
        String system;
        String task;
        if ("repository-v1".equals(spec.benchmarkVersion())) {
            var item = jdbc.queryForMap("select title,instructions_blob_id,base_commit from repository_work_item where run_id=?", spec.runId());
            String instructions = new String(blobs.get((String) item.get("instructions_blob_id")), StandardCharsets.UTF_8);
            system = "Edit the frozen repository snapshot using the six provided tools. " +
                    "Read relevant files, make a focused change, run checks, then submit a concise summary. " +
                    "A failed check must be reported honestly. Submit means ready for human review; changes are not applied to the live repository." +
                    PROTOCOL;
            task = "Task: " + item.get("title") + "\nBase commit: " + item.get("base_commit") + "\nInstructions:\n" + instructions;
        } else {
            task = workspaces.files(spec.runId(), new SnapshotRef(spec.seedSnapshotSha256())).get("REQUIREMENTS.md");
            if (task == null) throw new IllegalStateException("Benchmark requirements are missing");
            system = "Implement the Java task-tracker CLI described in REQUIREMENTS.md using the six provided tools. " +
                    "Use list_files and read_file to inspect the starter before changing it. " +
                    "A passing submit is required; the host evaluates the submitted snapshot." + PROTOCOL;
        }
        var memory = WorkingMemory.start(spec.runId(), system, task, profile.contextTokens(), profile.outputTokens());
        return storeConversation(WorkingMemory.write(memory));
    }
```

In `appendConversation`, add as the first lines:

```java
        byte[] previous = blobs.get(previousBlobId);
        if (WorkingMemory.isV2(previous))
            return storeConversation(WorkingMemory.write(WorkingMemory.fold(WorkingMemory.read(previous), reply, outcomes)));
        Map<String, Object> root = json.readValue(previous, new TypeReference<>() {});
```

(and delete the old `Map<String, Object> root = json.readValue(blobs.get(previousBlobId), ...)` line; the rest of the v1 path is unchanged).

In `callModel`, inside the `try (var attempt = telemetry.modelAttempt(call))` block, replace the adapter call line with:

```java
            ModelCall dispatched = call;
            byte[] conversation = blobs.get(call.conversationBlobId());
            if (WorkingMemory.isV2(conversation)) {
                var memory = WorkingMemory.read(conversation);
                if (!WorkingMemory.fits(memory)) throw new IllegalArgumentException("MODEL_CONTEXT_OVERFLOW");
                String rendered = blobs.put(CONVERSATION, json.writeValueAsBytes(WorkingMemory.render(memory)));
                dispatched = new ModelCall(call.runId(), call.invocationId(), call.attempt(),
                        call.profileRevision(), rendered, call.deadlineEpochMillis());
            }
            ModelReply reply = adapters.forProfile(profiles.get(call.profileRevision())).call(dispatched);
```

The existing `catch (RuntimeException failure)` already abandons the lease and maps `MODEL_CONTEXT_OVERFLOW` to the non-retryable failure the workflow turns into `CONTEXT_OVERFLOW`.

Replace `conversationBlob`:

```java
    private String conversationBlob(Object value) { return storeConversation(json.writeValueAsBytes(value)); }

    private String storeConversation(byte[] data) {
        if (data.length > MAX_CONVERSATION_BYTES)
            throw new IllegalArgumentException("MODEL_CONTEXT_OVERFLOW");
        return blobs.put(CONVERSATION, data);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test` then `./gradlew integrationTest --tests 'com.brianmehrman.harness.execution.RepositoryTaskIT'`
Expected: both PASS.

- [ ] **Step 5: Run the full build**

Run: `./gradlew build` (needs PostgreSQL, Temporal, Docker, `HARNESS_RUNNER_IMAGE`, `HARNESS_REPOSITORY_RUNNER_IMAGE`; see [docs/development.md](../../development.md)).
Expected: BUILD SUCCESSFUL. Record the date, commit, and test count for the PR.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/brianmehrman/harness/execution/RunActivitiesImpl.java src/test/java/com/brianmehrman/harness/execution/RepositoryTaskIT.java
git commit -m "feat: run coding conversations on bounded working memory"
```

---

### Task 4: Live Run 1, evidence, and PR 1

**Files:**
- Modify: `docs/evidence/local-task-tracker.md`, `docs/development.md` (one paragraph in "Durable coding workflow (Task 7)"), `docs/STATUS.md`

**Hypothesis for Run 1:** With the model-visible conversation bounded, `qwen3.5:9b` at 16,384 context no longer ends in `finishReason=length` replies without tool calls, and every turn's `inputTokens` stays at or below `maxInputTokens` (11,059).

- [ ] **Step 1: Start task-owned services and the three roles**

Follow [docs/development.md](../../development.md) ("Separate clean test project" and "Process roles") and the setup recorded in [baseline evidence](../../evidence/local-task-tracker.md): Compose project `harness-tryout`, a fresh PostgreSQL schema such as `working_memory_run1`, a fresh Temporal namespace, and the packaged JAR from `./gradlew bootJar`. Record the JAR SHA-256 (`shasum -a 256 build/libs/*.jar`) and the commit (`git rev-parse HEAD`).

- [ ] **Step 2: Freeze a new profile and run**

Use the [operator CLI](../../api.md): run the read-only tool probe against the installed `qwen3.5:9b`, save a new profile with 16,384 context, 4,096 output, temperature 0, and start a `task-tracker-v1` run. Record Ollama version, full model digest, profile ID, and run ID.

- [ ] **Step 3: Collect evidence**

After the run is terminal, query the run's records (replace `$RUN`):

```sql
select invocation_id, attempt, status, request_blob_id, response_blob_id from model_attempt where run_id='$RUN' order by invocation_id, attempt;
select invocation_id, status, exit_code, log_blob_id from runner_invocation where run_id='$RUN' order by invocation_id;
```

For each response blob, record `inputTokens`, `outputTokens`, `finishReason`, and whether it called tools. For each request blob, read `conversationBlobId` and record `WorkingMemory.estimateTokens` (or the rendered JSON length / 3) and `compacted.turns`. Record the evaluator verdict if a submit happened.

- [ ] **Step 4: Branch on the outcome**

- **Pass** (`passed=true`): retrieve the candidate JAR and source snapshot through the artifact API, rerun the evaluator on them in a fresh environment, and record both results. Go to Step 5.
- **Fail:** record the terminal reason and the specific cause from receipts (for example, compile errors from `lastCheck`, repeated rejected writes, the model never calling `build`). State the next hypothesis and which allowed lever it changes (prompt and protocol wording, tool result shape, or installed model). Go to Step 5; the next run happens in Task 5.

- [ ] **Step 5: Stop services**

Stop the three Java roles and `docker compose -p harness-tryout down` (never `-v`). Before stopping PostgreSQL, confirm runner cleanup the same way `RepositoryTaskIT` does: `select bool_and(cleaned) from runner_invocation where run_id='$RUN';` must be `true`.

- [ ] **Step 6: Update documentation**

- `docs/evidence/local-task-tracker.md`: add a "Working-memory Run 1" section with setup, the per-turn token table, terminal reason, diagnosis, and next hypothesis. Keep the earlier runs unchanged.
- `docs/development.md`, "Durable coding workflow (Task 7)": one paragraph saying new runs use conversation format v2 (working memory, K = 2, budget from the frozen profile), linking to the spec.
- `docs/STATUS.md`: update the Task 10 paragraph with Run 1's outcome and the next step.

Check links: `grep -o '](\.\{0,2\}[^)]*)' docs/STATUS.md docs/evidence/local-task-tracker.md docs/development.md` and confirm each relative target exists.

- [ ] **Step 7: Commit, push, and open PR 1**

```bash
git add docs/evidence/local-task-tracker.md docs/development.md docs/STATUS.md
git commit -m "docs: record working-memory live run 1"
git status --short && git diff origin/main --stat
git push -u origin feat/working-memory-context
gh pr create --title "Bound model context with a working-memory conversation" --body-file "$SCRATCHPAD/pr1-body.md"
```

Write the body to `$SCRATCHPAD/pr1-body.md` (the session scratchpad directory) first. It lists: what changed, the `./gradlew build` result with date and commit, Run 1 evidence and outcome, net new test methods (2), what each environment needs (no migration, no new configuration or secrets; new runs pick up v2 automatically; in-flight v1 runs continue as v1), and the next hypothesis if Run 1 failed. Review the staged files and body for secrets. Do not merge.

---

### Task 5: Iterate live runs until the evaluator passes (repeat per run)

Each iteration is its own focused branch from updated `main` after the previous PR merges (fetch, `git switch main`, `git pull --ff-only origin main`, verify clean, then `git switch -c feat/working-memory-run-N`).

- [ ] **Step 1: Write the hypothesis first.** One sentence in `docs/evidence/local-task-tracker.md` naming the observed cause from the previous run's receipts and the single lever changed:
  - Prompt and protocol wording: edit `PROTOCOL` or the system text in `RunActivitiesImpl.startConversation` using only public material.
  - Tool result shape: edit `ToolRouter` and extend the matching existing `ToolRouterTest` assertion; do not add a test method unless no existing test covers the shape.
  - Installed model: list installed models (`ollama list`), probe, freeze a new profile. No downloads without owner approval.
- [ ] **Step 2: Run the affected checks.** Code change: `./gradlew test` plus the focused integration test for the touched class, and `./gradlew build` before the PR. Model-only change: no code checks are needed.
- [ ] **Step 3: Run live** exactly as Task 4 Steps 1–5, with a fresh schema, namespace, and profile.
- [ ] **Step 4: Record evidence** as Task 4 Step 6.
- [ ] **Step 5: Checkpoint.** If this is the third consecutive failed live run, stop and report the evidence and the next hypothesis to the owner before any further run.
- [ ] **Step 6: Commit, push, and open a PR** as Task 4 Step 7. On a pass, also mark the C2/C3 rows evidenced and update `docs/STATUS.md` to hand off the remaining Task 10 items (`scripts/local_acceptance.py`, `liveLocalTest`, worker-crash and deployment scenarios). Confirm the PR merged before moving the handoff forward.
