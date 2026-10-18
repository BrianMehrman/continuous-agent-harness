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
    private static final String CHECK_ERROR = "Main.java:3: error: cannot find symbol | symbol: method " +
            "m".repeat(300) + "() | location: class Main";
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
                case 9 -> { // a NUL the store rejects must not break elision of the recorded call
                    reply = new ModelReply(note, List.of(new ToolRequest("p", "write_file",
                            write("Main.java", "absent", "z".repeat(12_000) + "\u0000"))), 1L, 1L, "stop");
                    outcomes = List.of(outcome(id, "write_file", Map.of("status", "ERROR", "code", "INVALID_ARGUMENTS",
                            "detail", "Content must be valid UTF-8 text")));
                }
                case 10 -> {
                    reply = new ModelReply(note, List.of(new ToolRequest("p", "build", "{}")), 1L, 1L, "stop");
                    outcomes = List.of(outcome(id, "build", Map.of("status", "FAILED", "exitCode", 1,
                            "errors", List.of(CHECK_ERROR), "logTail", "...")));
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
                assertTrue(reminder.get("content").toString().contains("Your reply was cut off at the output limit"),
                        "A capped reply must be named so the model writes less, not the same again");
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
        assertEquals(List.of(CHECK_ERROR), state.lastCheck().errors(), "Grouped error entries survive folding intact");
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
            if (i == 3) assertNull(WorkingMemory.stall(memory), "A write after the nudge is not a repeated check");
            memory = fold(memory, "build", "{}", Map.of("status", "FAILED", "exitCode", 1,
                    "errors", List.of(i % 2 == 0 ? failing : movedLine, "BUILD FAILED in " + (2 + i % 2) + "s"), "logTail", "..."));
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
    }

    @Test void taskLargerThanTheBudgetIsReportedInsteadOfCut() {
        var memory = WorkingMemory.start("run-1", "system rules", "t".repeat(20_000), 4096, 512);
        assertFalse(WorkingMemory.fits(memory));
        var next = WorkingMemory.fold(memory, new ModelReply("", List.of(), 1L, 1L, "stop"), List.of());
        assertFalse(WorkingMemory.fits(next));
        assertEquals("t".repeat(20_000), next.task(), "The task is never compacted");
        assertEquals(List.of(), WorkingMemory.errorLines("Compilation failed at line 12"));
    }

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
