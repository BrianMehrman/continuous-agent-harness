package com.brianmehrman.harness.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.benchmark.Evaluation;
import com.brianmehrman.harness.benchmark.Evaluator;
import com.brianmehrman.harness.model.ToolRequest;
import com.brianmehrman.harness.runner.InvocationRequest;
import com.brianmehrman.harness.runner.InvocationResult;
import com.brianmehrman.harness.runner.Runner;
import com.brianmehrman.harness.runs.BlobStore;
import com.brianmehrman.harness.workspace.SnapshotRef;
import com.brianmehrman.harness.workspace.WorkspaceStore;
import com.brianmehrman.harness.workspace.WriteRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import com.brianmehrman.harness.runner.Operation;
import tools.jackson.databind.json.JsonMapper;

class ToolRouterTest {
    private static final SnapshotRef START = new SnapshotRef("a".repeat(64));
    private static final SnapshotRef EDITED = new SnapshotRef("b".repeat(64));

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
        assertTrue(outcome.responseJson().contains("Use this sha256 as expectedSha256 for the next write_file of NOTES.md; no read_file is needed"),
                outcome.responseJson());
        assertFalse(outcome.responseJson().contains("snapshotSha256"), "The snapshot hash is host state, not a write precondition");
        assertTrue(outcome.responseJson().contains("\"changed\":true"), "A new file is a change");

        assertEquals("run-1", workspace.lastWrite.runId());
        assertEquals(START, workspace.lastWrite.parent());
        assertEquals("run-1:turn-1:tool-1", workspace.lastWrite.invocationId());

        ToolOutcome same = router.route(new ToolInvocation("run-1", "run-1:turn-1:tool-2", START,
                new ToolRequest("provider-4", "write_file", "{\"path\":\"README.md\",\"expectedSha256\":" +
                        "\"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824\",\"content\":\"hello\"}"),
                System.currentTimeMillis() + 10_000));
        assertTrue(same.responseJson().contains("\"changed\":false"), "The host, not the model, says an identical rewrite changed nothing");

        workspace.lastWrite = null;
        ToolOutcome stale = router.route(new ToolInvocation("run-1", "run-1:turn-2:tool-1", START,
                new ToolRequest("provider-2", "write_file", "{\"path\":\"README.md\",\"expectedSha256\":\"absent\",\"content\":\"bye\"}"),
                System.currentTimeMillis() + 10_000));
        assertTrue(stale.responseJson().contains("INVALID_ARGUMENTS"));
        assertTrue(stale.responseJson().contains("current sha256 is 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"));
        assertNull(workspace.lastWrite, "A stale hash must not reach the workspace");

        ToolOutcome refused = router.route(new ToolInvocation("run-1", "run-1:turn-3:tool-1", START,
                new ToolRequest("provider-3", "write_file", "{\"path\":\"build.gradle\",\"expectedSha256\":\"absent\",\"content\":\"x\"}"),
                System.currentTimeMillis() + 10_000));
        assertTrue(refused.responseJson().contains("Path is not writable"), refused.responseJson());
        assertFalse(refused.responseJson().contains("JSON"), "A policy refusal must not be reported as malformed JSON");
    }

    @Test void unknownToolIsAResultAndNeverReachesTrustedDependencies() {
        var workspace = new MemoryWorkspace();
        var router = new ToolRouter(workspace, new NoRunner(), new NoEvaluator(), new MemoryBlobs());
        ToolOutcome outcome = router.route(new ToolInvocation("run-1", "inv-1", START,
                new ToolRequest("provider-1", "shell", "{}"), System.currentTimeMillis() + 10_000));

        assertEquals(START, outcome.snapshot());
        assertTrue(outcome.responseJson().contains("UNKNOWN_TOOL"));
        assertNull(workspace.lastWrite);
    }

    @Test void listAndReadUseOnlyTheCurrentImmutableSnapshot() {
        var workspace = new MemoryWorkspace();
        var router = new ToolRouter(workspace, new NoRunner(), new NoEvaluator(), new MemoryBlobs());
        ToolOutcome listed = router.route(new ToolInvocation("run-1", "list-1", START,
                new ToolRequest("p-list", "list_files", "{}"), System.currentTimeMillis() + 10_000));
        ToolOutcome read = router.route(new ToolInvocation("run-1", "read-1", START,
                new ToolRequest("p-read", "read_file", "{\"path\":\"README.md\"}"),
                System.currentTimeMillis() + 10_000));

        assertTrue(listed.responseJson().contains("README.md"));
        assertTrue(read.responseJson().contains("hello"));
        assertTrue(read.responseJson().contains("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"));
        assertEquals(START, read.snapshot());
        assertEquals(START, workspace.lastReadSnapshot);
        ToolOutcome missing = router.route(new ToolInvocation("run-1", "read-2", START,
                new ToolRequest("p-read", "read_file", "{\"path\":\"MISSING.md\"}"), System.currentTimeMillis() + 10_000));
        assertTrue(missing.responseJson().contains("FILE_NOT_FOUND"));
        assertTrue(missing.responseJson().contains("MISSING.md"));
    }

    @Test void runTestsUsesFixedRunnerOperationAndCurrentSnapshot() {
        var runner = new RecordingRunner();
        var router = new ToolRouter(new MemoryWorkspace(), runner, new NoEvaluator(), new MemoryBlobs());
        ToolOutcome result = router.route(new ToolInvocation("run-1", "test-1", START,
                new ToolRequest("p-test", "run_tests", "{}"), System.currentTimeMillis() + 10_000));

        assertEquals(Operation.TEST, runner.lastRequest.operation());
        assertEquals(START, runner.lastRequest.snapshot());
        assertEquals("test-1", runner.lastRequest.invocationId());
        assertTrue(result.responseJson().contains("\"exitCode\":1"));
        assertTrue(result.responseJson().contains("Compilation failed at line 12"));
        assertEquals(START, result.snapshot());
        var errors = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result.responseJson()).path("errors");
        assertEquals(List.of(
                "src/Main.java:12: error: cannot find symbol | symbol: method missing() | location: variable tracker of type TaskTracker",
                "TaskTrackerTest > addsTask() FAILED | org.opentest4j.AssertionFailedError: expected: <1> but was: <2>"),
                errors.valueStream().map(e -> e.asText()).toList(),
                "Errors keep javac and JUnit context lines and drop Gradle's repeated output");
        assertTrue(result.responseJson().contains("\"logTail\""));
        assertFalse(result.responseJson().contains("\"log\":"));
    }

    @Test void rejectedSubmissionRemainsAVisibleToolResult() {
        var evaluator = new RejectingEvaluator();
        var router = new ToolRouter(new MemoryWorkspace(), new NoRunner(), evaluator, new MemoryBlobs());
        ToolOutcome result = router.route(new ToolInvocation("run-1", "submit-1", EDITED,
                new ToolRequest("p-submit", "submit", "{\"summary\":\"ready\"}"),
                System.currentTimeMillis() + 10_000));

        assertTrue(result.attemptedSubmission());
        assertFalse(result.passed());
        assertEquals(EDITED, evaluator.submittedSnapshot);
        assertTrue(result.responseJson().contains("REJECTED"));
    }

    @Test void modelSuppliedRunIdentityIsRejectedBeforeAnyWrite() {
        var workspace = new MemoryWorkspace();
        var router = new ToolRouter(workspace, new NoRunner(), new NoEvaluator(), new MemoryBlobs());
        ToolOutcome result = router.route(new ToolInvocation("run-1", "write-1", START,
                new ToolRequest("provider-1", "write_file", "{\"path\":\"README.md\",\"expectedSha256\":\"absent\",\"content\":\"hello\",\"runId\":\"other\"}"),
                System.currentTimeMillis() + 10_000));

        assertTrue(result.responseJson().contains("INVALID_ARGUMENTS"));
        assertNull(workspace.lastWrite);
    }

    @Test void malformedToolJsonReturnsAnErrorWithoutRunningAnything() {
        var workspace = new MemoryWorkspace();
        var router = new ToolRouter(workspace, new NoRunner(), new NoEvaluator(), new MemoryBlobs());
        ToolOutcome result = router.route(new ToolInvocation("run-1", "write-1", START,
                new ToolRequest("provider-1", "write_file", "{"), System.currentTimeMillis() + 10_000));

        assertTrue(result.responseJson().contains("INVALID_ARGUMENTS"));
        assertNull(workspace.lastWrite);
    }

    @Test void toolOutsideRoleSetIsRefused() {
        var workspace = new MemoryWorkspace();
        var router = new ToolRouter(workspace, new NoRunner(), new NoEvaluator(), new MemoryBlobs());
        var call = new ToolInvocation("run-1", "write-1", START,
                new ToolRequest("provider-1", "write_file",
                        "{\"path\":\"NOTES.md\",\"expectedSha256\":\"absent\",\"content\":\"hello\"}"),
                System.currentTimeMillis() + 10_000, List.of("list_files", "read_file"));

        ToolOutcome outcome = router.route(call);

        assertEquals("TOOL_NOT_ALLOWED", JsonMapper.builder().build()
                .readTree(outcome.responseJson()).path("code").asText());
        assertEquals(START, outcome.snapshot());
        assertNull(outcome.failureCode());
        assertNull(workspace.lastWrite);
    }

    @Test void deniedPathsAreHiddenAndRefused() {
        var workspace = new MemoryWorkspace(Map.of(
                "src/A.java", "class A {}", ".env", "hidden", "config/.env.local", "hidden",
                "deploy/server.pem", "hidden", ".secrets/x", "hidden", "keys/id_ed25519", "hidden",
                "keys/id_rsa_backup", "hidden", "deploy/server.key", "hidden", "deploy/store.p12", "hidden"));
        var router = new ToolRouter(workspace, new NoRunner(), new NoEvaluator(), new MemoryBlobs());
        var json = JsonMapper.builder().build();
        long deadline = System.currentTimeMillis() + 10_000;

        ToolOutcome listed = router.route(new ToolInvocation("run-1", "list-1", START,
                new ToolRequest("provider-1", "list_files", "{}"), deadline));
        assertEquals(List.of("src/A.java"), json.readTree(listed.responseJson()).path("paths")
                .valueStream().map(node -> node.asText()).toList());
        for (String path : List.of(".env", "config/.env.local", "deploy/server.pem", ".secrets/x",
                "keys/id_ed25519", "keys/id_rsa_backup", "deploy/server.key", "deploy/store.p12")) {
            for (String tool : List.of("read_file", "write_file")) {
                String args = tool.equals("read_file") ? "{\"path\":\"" + path + "\"}"
                        : "{\"path\":\"" + path + "\",\"expectedSha256\":\"absent\",\"content\":\"x\"}";
                ToolOutcome outcome = router.route(new ToolInvocation("run-1", tool + path, START,
                        new ToolRequest("provider-1", tool, args), deadline));
                assertEquals("PATH_DENIED", json.readTree(outcome.responseJson()).path("code").asText(),
                        tool + " " + path);
                assertEquals(START, outcome.snapshot());
                assertNull(outcome.failureCode());
            }
        }
        assertNull(workspace.lastWrite);
        assertFalse(ModelPathPolicy.denied("src/environment.java"));
    }

private static final class MemoryWorkspace implements WorkspaceStore {
        private WriteRequest lastWrite;
        private SnapshotRef lastReadSnapshot;
        private final Map<String, String> contents;
        private MemoryWorkspace() { this(Map.of("README.md", "hello")); }
        private MemoryWorkspace(Map<String, String> contents) { this.contents = contents; }
        @Override public SnapshotRef seed(String runId, String benchmarkVersion) { throw new AssertionError(); }
        @Override public SortedMap<String, String> files(String runId, SnapshotRef ref) {
            lastReadSnapshot = ref;
            return new TreeMap<>(contents);
        }
        @Override public SnapshotRef write(WriteRequest request) {
            if (request.path().equals("build.gradle")) throw new IllegalArgumentException("Path is not writable");
            lastWrite = request;
            return EDITED;
        }
    }

    private static final class RecordingRunner implements Runner {
        private InvocationRequest lastRequest;
        @Override public void ensureStarted(InvocationRequest request) { lastRequest = request; }
        @Override public Optional<InvocationResult> result(String invocationId) {
            return Optional.of(new InvocationResult(invocationId, "c".repeat(64), "FAILED", 1,
                    "d".repeat(64), false, null, 1, false));
        }
        @Override public void cancel(String invocationId) { throw new AssertionError(); }
        @Override public boolean stopConfirmed(String invocationId) { throw new AssertionError(); }
    }

    private static final class RejectingEvaluator implements Evaluator {
        private SnapshotRef submittedSnapshot;
        @Override public Evaluation evaluate(String runId, String invocationId, SnapshotRef snapshot,
                long seed, long deadlineEpochMillis) {
            submittedSnapshot = snapshot;
            return new Evaluation(false, snapshot.sha256(), "e".repeat(64), "v1", seed,
                    java.util.List.of("case-1"), 2, Evaluation.Outcome.REJECTED);
        }
    }

    private static final String CANNOT_FIND = """
            src/Main.java:12: error: cannot find symbol
                    tracker.missing();
                           ^
              symbol:   method missing()
              location: variable tracker of type TaskTracker
            """;
    private static final String LOG = "Compilation failed at line 12\n" + CANNOT_FIND + CANNOT_FIND + """
            TaskTrackerTest > addsTask() FAILED
                org.opentest4j.AssertionFailedError: expected: <1> but was: <2>
                    at app//TaskTrackerTest.addsTask(TaskTrackerTest.java:20)
            """;

    private static final class MemoryBlobs implements BlobStore {
        @Override public String put(String mediaType, byte[] bytes) { throw new AssertionError(); }
        @Override public byte[] get(String digest) {
            assertEquals("d".repeat(64), digest);
            return LOG.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static final class NoRunner implements Runner {
        @Override public void ensureStarted(InvocationRequest request) { throw new AssertionError(); }
        @Override public Optional<InvocationResult> result(String invocationId) { throw new AssertionError(); }
        @Override public void cancel(String invocationId) { throw new AssertionError(); }
        @Override public boolean stopConfirmed(String invocationId) { throw new AssertionError(); }
    }

    private static final class NoEvaluator implements Evaluator {
        @Override public Evaluation evaluate(String runId, String invocationId, SnapshotRef snapshot,
                long seed, long deadlineEpochMillis) { throw new AssertionError(); }
    }
}
