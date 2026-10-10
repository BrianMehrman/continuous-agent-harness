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
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import com.brianmehrman.harness.runner.Operation;

class ToolRouterTest {
    private static final SnapshotRef START = new SnapshotRef("a".repeat(64));
    private static final SnapshotRef EDITED = new SnapshotRef("b".repeat(64));

    @Test void writeUsesHarnessSnapshotAndReturnsNewSnapshot() {
        var workspace = new MemoryWorkspace();
        var router = new ToolRouter(workspace, new NoRunner(), new NoEvaluator(), new MemoryBlobs());
        var call = new ToolInvocation("run-1", "run-1:turn-1:tool-1", START,
                new ToolRequest("provider-1", "write_file", "{\"path\":\"README.md\",\"expectedSha256\":\"absent\",\"content\":\"hello\"}"),
                System.currentTimeMillis() + 10_000);

        ToolOutcome outcome = router.route(call);

        assertEquals(EDITED, outcome.snapshot());
        assertEquals("provider-1", outcome.providerCallId());
        assertTrue(outcome.responseJson().contains("\"status\":\"OK\""));
        assertEquals("run-1", workspace.lastWrite.runId());
        assertEquals(START, workspace.lastWrite.parent());
        assertEquals("run-1:turn-1:tool-1", workspace.lastWrite.invocationId());
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

    @Test void evaluatorInfrastructureFailureWithoutArtifactStaysVisible() {
        Evaluator failed = (runId, invocationId, snapshot, seed, deadline) ->
                new Evaluation(false, snapshot.sha256(), null, "v1", seed,
                        java.util.List.of(), 0, Evaluation.Outcome.INFRASTRUCTURE_FAILED);
        var router = new ToolRouter(new MemoryWorkspace(), new NoRunner(), failed, new MemoryBlobs());
        ToolOutcome outcome = router.route(new ToolInvocation("run-1", "submit-1", START,
                new ToolRequest("provider-1", "submit", "{\"summary\":\"ready\"}"),
                System.currentTimeMillis() + 10_000));

        assertTrue(outcome.responseJson().contains("INFRASTRUCTURE_FAILED"));
        assertFalse(outcome.passed());
    }

    private static final class MemoryWorkspace implements WorkspaceStore {
        private WriteRequest lastWrite;
        private SnapshotRef lastReadSnapshot;
        @Override public SnapshotRef seed(String runId, String benchmarkVersion) { throw new AssertionError(); }
        @Override public SortedMap<String, String> files(String runId, SnapshotRef ref) {
            lastReadSnapshot = ref;
            var files = new TreeMap<String, String>();
            files.put("README.md", "hello");
            return files;
        }
        @Override public SnapshotRef write(WriteRequest request) {
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

    private static final class MemoryBlobs implements BlobStore {
        @Override public String put(String mediaType, byte[] bytes) { throw new AssertionError(); }
        @Override public byte[] get(String digest) {
            assertEquals("d".repeat(64), digest);
            return "Compilation failed at line 12".getBytes(StandardCharsets.UTF_8);
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
