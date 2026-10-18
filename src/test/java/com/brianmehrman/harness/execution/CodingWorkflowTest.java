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

    @Test void secondTextOnlyReplyStallsAndEndsWhenNobodyResumes() {
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

            assertEquals("STALLED", workflow.run(spec).status());
            assertEquals(2, fixture.modelAttempts.size());
            assertTrue(fixture.events.contains("PAUSED"));
            assertTrue(fixture.events.contains("STOPPED"));
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
            assertTrue(fixture.events.contains("PAUSED"));
        }
    }

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
        private final List<String> events = new ArrayList<>();
        private String hint;
        private int textOnly;

        private ScriptedActivities() {
            this(List.of(reply("read_file"), reply("write_file"), reply("run_tests"),
                    reply("write_file", "submit"), reply("write_file", "submit")));
        }

        private ScriptedActivities(List<ModelReply> replies) { this.replies = replies; }

        void reset() { modelCalls = 0; submissions = 0; toolSnapshots.clear(); publishedStatuses.clear(); events.clear(); }

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
                List<ToolOutcome> outcomes) {
            if (reply.tools().isEmpty() && ++textOnly >= 2)
                return new ConversationStep("2".repeat(64), null,
                        new WorkingMemory.StallVerdict("NO_TOOL_CALL", textOnly, List.of("turn reply had no tool call")), null);
            return new ConversationStep("2".repeat(64), null, null, null);
        }
        @Override public String resumeConversation(String blobId, String hint) { this.hint = hint; return blobId; }
        @Override public void publishEvent(String runId, long sequence, long version, String status,
                String snapshotSha256, String type, java.util.Map<String, Object> detail) { events.add(type); }
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
