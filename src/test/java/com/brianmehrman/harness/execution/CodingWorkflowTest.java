package com.brianmehrman.harness.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.model.ModelCall;
import com.brianmehrman.harness.model.ModelReply;
import com.brianmehrman.harness.model.Prices;
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
                    Limits.codingDefaults(), PausePolicy.defaults(),
                    new RunTerms("developer", "role-digest", "role-blob",
                            List.of("read_file", "write_file", "run_tests", "submit"), "model", false, null));

            RunView result = workflow.run(spec);

            assertEquals("SUCCEEDED", result.status());
            assertEquals(FINAL, result.snapshotSha256());
            assertEquals(2, fixture.submissions);
            assertEquals(List.of(SEED, SEED, BROKEN, BROKEN, REPAIRED, REPAIRED, FINAL),
                    fixture.toolSnapshots);
            assertEquals("SUCCEEDED", fixture.publishedStatuses.getLast());
            assertTrue(fixture.toolAllowances.stream().allMatch(tools -> tools.equals(
                    List.of("read_file", "write_file", "run_tests", "submit"))));
            assertEquals(7, fixture.toolAllowances.size());
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
                    Limits.codingDefaults(), PausePolicy.defaults(),
                    new RunTerms("developer", "role-digest", "role-blob", List.of("read_file"),
                            "model", false, null));

            assertEquals("STALLED", workflow.run(spec).status());
            assertEquals(2, fixture.modelAttempts.size());
            assertEquals(List.of(List.of("read_file"), List.of("read_file")), fixture.modelTools);
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
            assertEquals("REJECTED_LIMIT", workflow.resume(new ResumeCommand("resume-no-extra", paused.version(), 0, 0, 0,
                    null)).deliveryStatus(), "A resume must extend the turn limit that was reached");
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

            var noResumes = new PausePolicy(3, 3, 6, 7L * 24 * 3_600_000, 0, 40, 100, 30, 120, 300, 90);
            var exhausted = client.newWorkflowStub(CodingWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId(RUN + "-exhausted").setTaskQueue("coding-pause-test").build());
            fixture.reset();
            assertEquals("LIMIT_EXHAUSTED", exhausted.run(new RunSpec(RUN + "-exhausted", "start-4",
                    "f".repeat(64), "task-tracker-v1", "0".repeat(64), "evaluator-v1", "profile-1",
                    "1".repeat(64), SEED, Limits.codingDefaults().withModelTurns(1), noResumes)).status());
            assertEquals(List.of("PAUSED", "STOPPED"), fixture.events, "A run with no resumes left still records its pause");
        }
    }

    @Test void costLimitPausesAtEffectiveLimitThenResumesWithExtraBudget() throws Exception {
        var fixture = new ScriptedActivities(List.of(
                pricedReply("read_file"), pricedReply("read_file"), pricedReply("read_file"),
                pricedReply("submit")));
        fixture.submitPassOn = 1;
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker("coding-cost-test");
            worker.registerWorkflowImplementationTypes(UnversionedCodingWorkflow.class);
            worker.registerActivitiesImplementations(fixture);
            environment.start();
            var workflow = environment.getWorkflowClient().newWorkflowStub(CodingWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId(RUN).setTaskQueue("coding-cost-test").build());
            var cost = new CostTerms(new Prices(1_000_000, 5_000_000, 0, 0),
                    20_000, 2_000, 100_000L, 10);
            var spec = new RunSpec(RUN, "start-cost", "f".repeat(64), "task-tracker-v1",
                    "0".repeat(64), "evaluator-v1", "profile-1", "1".repeat(64), SEED,
                    Limits.codingDefaults(), PausePolicy.defaults(),
                    new RunTerms("developer", "role-digest", "role-blob", List.of("read_file", "submit"),
                            "model", true, cost));
            var result = WorkflowClient.execute(workflow::run, spec);

            RunView paused = awaitStatus(workflow, "PAUSED");
            assertEquals(3, fixture.modelCalls, "Call four must wait for added budget");
            assertEquals("REJECTED_LIMIT", workflow.resume(new ResumeCommand(
                    "cost-zero", paused.version(), 0, 0, 0, null, 0)).deliveryStatus());
            assertEquals("REJECTED_LIMIT", workflow.resume(new ResumeCommand(
                    "cost-small", paused.version(), 0, 0, 0, null, 10_000)).deliveryStatus());
            assertEquals("APPLIED", workflow.resume(new ResumeCommand(
                    "cost-enough", paused.version(), 0, 0, 0, null, 50_000)).deliveryStatus());

            assertEquals("SUCCEEDED", result.get(5, TimeUnit.SECONDS).status());
            assertEquals(4, fixture.modelCalls);
            var pausedEvent = fixture.eventDetails.get(fixture.events.indexOf("PAUSED"));
            assertEquals("COST_LIMIT", pausedEvent.get("reason"));
            assertEquals(3, ((Number) pausedEvent.get("turnsUsed")).intValue());
            assertEquals(90_000L, ((Number) ((java.util.Map<?, ?>) pausedEvent.get("cost"))
                    .get("effectiveLimitMicros")).longValue());
            assertEquals(30_000L, ((Number) ((java.util.Map<?, ?>) pausedEvent.get("cost"))
                    .get("nextCallWorstCaseMicros")).longValue());
            var resumedEvent = fixture.eventDetails.get(fixture.events.indexOf("RESUMED"));
            assertEquals(50_000L, ((Number) resumedEvent.get("extraCostMicros")).longValue());
        }
    }

    private static ModelReply pricedReply(String tool) {
        return new ModelReply("", List.of(new ToolRequest("provider-1", tool, "{}")),
                20_000L, 2_000L, "tool_calls");
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
        private final List<List<String>> toolAllowances = new ArrayList<>();
        private final List<String> publishedStatuses = new ArrayList<>();
        private int modelCalls;
        private final List<Integer> modelAttempts = new ArrayList<>();
        private final List<List<String>> modelTools = new ArrayList<>();
        private CountDownLatch toolEntered;
        private CountDownLatch toolRelease;
        private String cancelledRunner;
        private long modelDelayMillis;
        private int submissions;
        private int submitPassOn = 2;
        private boolean released;
        private int unconfirmedStops;
        private int stopRequests;
        private final List<String> events = new ArrayList<>();
        private final List<java.util.Map<String, Object>> eventDetails = new ArrayList<>();
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
            modelTools.add(call.tools());
            return replies.get(modelCalls++);
        }
        @Override public ToolOutcome invokeTool(ToolInvocation call) {
            toolAllowances.add(call.allowedTools());
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
            boolean passed = submit && submissions == submitPassOn;
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
                String snapshotSha256, String type, java.util.Map<String, Object> detail) {
            events.add(type);
            eventDetails.add(detail);
        }
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
