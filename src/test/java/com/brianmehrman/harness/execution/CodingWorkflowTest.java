package com.brianmehrman.harness.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.model.ModelCall;
import com.brianmehrman.harness.model.ModelReply;
import com.brianmehrman.harness.model.ToolRequest;
import com.brianmehrman.harness.workspace.SnapshotRef;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.testing.TestWorkflowEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CodingWorkflowTest {
    private static final String RUN = "run-" + "a".repeat(64);
    private static final String SEED = "b".repeat(64);
    private static final String BROKEN = "c".repeat(64);
    private static final String REPAIRED = "d".repeat(64);
    private static final String FINAL = "e".repeat(64);

    @Test void failedSubmissionCanBeRepairedWithinTheBudget() {
        var fixture = new ScriptedActivities();
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker("coding-test");
            worker.registerWorkflowImplementationTypes(UnversionedCodingWorkflow.class);
            worker.registerActivitiesImplementations(fixture);
            environment.start();
            var workflow = environment.getWorkflowClient().newWorkflowStub(CodingWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId(RUN).setTaskQueue("coding-test").build());
            var spec = new RunSpec(RUN, "start-1", "f".repeat(64), "task-tracker-v1",
                    "0".repeat(64), "evaluator-v1", "profile-1", "1".repeat(64), SEED,
                    Limits.codingDefaults());

            RunView result = workflow.run(spec);

            assertEquals("SUCCEEDED", result.status());
            assertEquals(FINAL, result.snapshotSha256());
            assertEquals(2, fixture.submissions);
            assertEquals(List.of(SEED, SEED, BROKEN, BROKEN, REPAIRED, REPAIRED, FINAL),
                    fixture.toolSnapshots);
            assertEquals("SUCCEEDED", fixture.publishedStatuses.getLast());
            assertTrue(fixture.released);
        }
    }

    @Test void twoTextOnlyRepliesFailAfterOneProtocolReminder() {
        var fixture = new ScriptedActivities(List.of(
                new ModelReply("done", List.of(), 1L, 1L, "stop"),
                new ModelReply("still done", List.of(), 1L, 1L, "stop")));
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker("coding-text-test");
            worker.registerWorkflowImplementationTypes(UnversionedCodingWorkflow.class);
            worker.registerActivitiesImplementations(fixture);
            environment.start();
            var workflow = environment.getWorkflowClient().newWorkflowStub(CodingWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId(RUN).setTaskQueue("coding-text-test").build());
            var spec = new RunSpec(RUN, "start-1", "f".repeat(64), "task-tracker-v1",
                    "0".repeat(64), "evaluator-v1", "profile-1", "1".repeat(64), SEED,
                    Limits.codingDefaults());

            assertEquals("FAILED", workflow.run(spec).status());
            assertEquals(2, fixture.modelAttempts.size());
        }
    }

    @Test void cancellationRequestsRunnerStopBeforeTerminalState() throws Exception {
        var fixture = new ScriptedActivities(List.of(ScriptedActivities.reply("run_tests")));
        fixture.toolEntered = new CountDownLatch(1);
        fixture.toolRelease = new CountDownLatch(1);
        fixture.unconfirmedStops = 2;
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker("coding-runner-cancel-test");
            worker.registerWorkflowImplementationTypes(UnversionedCodingWorkflow.class);
            worker.registerActivitiesImplementations(fixture);
            environment.start();
            var workflow = environment.getWorkflowClient().newWorkflowStub(CodingWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId(RUN).setTaskQueue("coding-runner-cancel-test").build());
            var spec = new RunSpec(RUN, "start-1", "f".repeat(64), "task-tracker-v1",
                    "0".repeat(64), "evaluator-v1", "profile-1", "1".repeat(64), SEED,
                    Limits.codingDefaults());
            var result = WorkflowClient.execute(workflow::run, spec);
            assertTrue(fixture.toolEntered.await(5, TimeUnit.SECONDS));

            assertEquals("APPLIED", workflow.cancel(new CancelCommand("cancel-runner", 1)).deliveryStatus());

            assertEquals(RUN + ":turn-1:tool-1", fixture.cancelledRunner);
            assertEquals("CANCELLED", result.get(5, TimeUnit.SECONDS).status());
            assertTrue(fixture.publishedStatuses.contains("STOPPING"));
            assertTrue(fixture.stopRequests >= 3);
        }
    }

    @Test void lateModelResultCannotDispatchToolsAfterTheRunDeadline() {
        var fixture = new ScriptedActivities();
        fixture.modelDelayMillis = 100;
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker("coding-deadline-test");
            worker.registerWorkflowImplementationTypes(UnversionedCodingWorkflow.class);
            worker.registerActivitiesImplementations(fixture);
            environment.start();
            var workflow = environment.getWorkflowClient().newWorkflowStub(CodingWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId(RUN).setTaskQueue("coding-deadline-test").build());
            var limits = new Limits(40, 100, 80, 2, 2, 180_000, 120_000, 180_000, 10_000, 50);
            var spec = new RunSpec(RUN, "start-1", "f".repeat(64), "task-tracker-v1",
                    "0".repeat(64), "evaluator-v1", "profile-1", "1".repeat(64), SEED, limits);

            assertEquals("EXPIRED", workflow.run(spec).status());
            assertTrue(fixture.toolSnapshots.isEmpty());
        }
    }

    private static final class ScriptedActivities implements RunActivities {
        private final List<ModelReply> replies;
        private final List<String> toolSnapshots = new ArrayList<>();
        private final List<String> publishedStatuses = new ArrayList<>();
        private int modelCalls;
        private final List<Integer> modelAttempts = new ArrayList<>();
        private CountDownLatch toolEntered;
        private CountDownLatch toolRelease;
        private String cancelledRunner;
        private long modelDelayMillis;
        private int submissions;
        private boolean released;
        private int unconfirmedStops;
        private int stopRequests;

        private ScriptedActivities() {
            this(List.of(reply("read_file"), reply("write_file"), reply("run_tests"),
                    reply("write_file", "submit"), reply("write_file", "submit")));
        }

        private ScriptedActivities(List<ModelReply> replies) { this.replies = replies; }

        @Override public boolean acquireAdmission(String runId) { return true; }
        @Override public void releaseAdmission(String runId) { released = true; }
        @Override public String startConversation(RunSpec spec) { return "2".repeat(64); }
        @Override public ModelReply callModel(ModelCall call) {
            if (modelDelayMillis > 0) {
                try {
                    Thread.sleep(modelDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            modelAttempts.add(call.attempt());
            return replies.get(modelCalls++);
        }
        @Override public ToolOutcome invokeTool(ToolInvocation call) {
            if (toolEntered != null && "run_tests".equals(call.request().name())) {
                toolEntered.countDown();
                try {
                    if (!toolRelease.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test runner release timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            toolSnapshots.add(call.snapshot().sha256());
            String name = call.request().name();
            SnapshotRef snapshot = switch (name) {
                case "write_file" -> new SnapshotRef(switch (toolSnapshots.size()) {
                    case 2 -> BROKEN;
                    case 4 -> REPAIRED;
                    default -> FINAL;
                });
                default -> call.snapshot();
            };
            boolean submit = "submit".equals(name);
            if (submit) submissions++;
            boolean passed = submit && submissions == 2;
            return new ToolOutcome(call.invocationId(), call.request().providerCallId(), name,
                    snapshot, "{\"status\":\"OK\"}", submit, passed);
        }
        @Override public ConversationStep appendConversation(String previousBlobId, ModelReply reply,
                List<ToolOutcome> outcomes) { return new ConversationStep("2".repeat(64), null, null, null); }
        @Override public String resumeConversation(String blobId, String hint) { return blobId; }
        @Override public void publish(String runId, long sequence, long version, String status,
                String snapshotSha256, String terminalResultBlobId) { publishedStatuses.add(status); }
        @Override public boolean cancelRunner(String invocationId) {
            cancelledRunner = invocationId;
            stopRequests++;
            if (toolRelease != null) toolRelease.countDown();
            return unconfirmedStops-- <= 0;
        }

        private static ModelReply reply(String... names) {
            var calls = new ArrayList<ToolRequest>();
            for (int i = 0; i < names.length; i++)
                calls.add(new ToolRequest("provider-" + i, names[i], "{}"));
            return new ModelReply("", calls, 10L, 10L, "tool_calls");
        }
    }
}
