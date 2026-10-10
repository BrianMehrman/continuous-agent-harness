package com.brianmehrman.harness.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.model.ModelCall;
import com.brianmehrman.harness.model.ModelReply;
import com.brianmehrman.harness.model.ToolRequest;
import com.brianmehrman.harness.workspace.SnapshotRef;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.failure.ApplicationFailure;
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
            worker.registerWorkflowImplementationTypes(CodingWorkflowImpl.class);
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

    @Test void uncertainModelCallConsumesAnExplicitSecondAttempt() {
        var fixture = new ScriptedActivities();
        fixture.unknownFirstCall = true;
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker("coding-retry-test");
            worker.registerWorkflowImplementationTypes(CodingWorkflowImpl.class);
            worker.registerActivitiesImplementations(fixture);
            environment.start();
            var workflow = environment.getWorkflowClient().newWorkflowStub(CodingWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId(RUN).setTaskQueue("coding-retry-test").build());
            var spec = new RunSpec(RUN, "start-1", "f".repeat(64), "task-tracker-v1",
                    "0".repeat(64), "evaluator-v1", "profile-1", "1".repeat(64), SEED,
                    Limits.codingDefaults());

            assertEquals("SUCCEEDED", workflow.run(spec).status());
            assertEquals(List.of(1, 2, 1, 1, 1, 1), fixture.modelAttempts);
        }
    }

    @Test void acceptedCancellationPreventsThePendingModelReplyFromDispatchingTools() throws Exception {
        var fixture = new ScriptedActivities();
        fixture.modelEntered = new CountDownLatch(1);
        fixture.modelRelease = new CountDownLatch(1);
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker("coding-cancel-test");
            worker.registerWorkflowImplementationTypes(CodingWorkflowImpl.class);
            worker.registerActivitiesImplementations(fixture);
            environment.start();
            var workflow = environment.getWorkflowClient().newWorkflowStub(CodingWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId(RUN).setTaskQueue("coding-cancel-test").build());
            var spec = new RunSpec(RUN, "start-1", "f".repeat(64), "task-tracker-v1",
                    "0".repeat(64), "evaluator-v1", "profile-1", "1".repeat(64), SEED,
                    Limits.codingDefaults());
            var result = WorkflowClient.execute(workflow::run, spec);
            assertTrue(fixture.modelEntered.await(5, TimeUnit.SECONDS));

            assertEquals("APPLIED", workflow.cancel(new CancelCommand("cancel-1", 1)).deliveryStatus());
            assertEquals("REJECTED_CONFLICT", workflow.cancel(new CancelCommand("cancel-1", 2)).deliveryStatus());
            assertEquals("CANCEL_REQUESTED", workflow.current().status());
            fixture.modelRelease.countDown();

            assertEquals("CANCELLED", result.get(5, TimeUnit.SECONDS).status());
            assertTrue(fixture.toolSnapshots.isEmpty());
        }
    }

    @Test void twoTextOnlyRepliesFailAfterOneProtocolReminder() {
        var fixture = new ScriptedActivities(List.of(
                new ModelReply("done", List.of(), 1L, 1L, "stop"),
                new ModelReply("still done", List.of(), 1L, 1L, "stop")));
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker("coding-text-test");
            worker.registerWorkflowImplementationTypes(CodingWorkflowImpl.class);
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

    @Test void contextOverflowStopsWithoutAnInfrastructureRetry() {
        var fixture = new ScriptedActivities();
        fixture.contextOverflowFirstCall = true;
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker("coding-overflow-test");
            worker.registerWorkflowImplementationTypes(CodingWorkflowImpl.class);
            worker.registerActivitiesImplementations(fixture);
            environment.start();
            var workflow = environment.getWorkflowClient().newWorkflowStub(CodingWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId(RUN).setTaskQueue("coding-overflow-test").build());
            var spec = new RunSpec(RUN, "start-1", "f".repeat(64), "task-tracker-v1",
                    "0".repeat(64), "evaluator-v1", "profile-1", "1".repeat(64), SEED,
                    Limits.codingDefaults());

            assertEquals("CONTEXT_OVERFLOW", workflow.run(spec).status());
            assertEquals(List.of(1), fixture.modelAttempts);
        }
    }

    @Test void cancellationRequestsRunnerStopBeforeTerminalState() throws Exception {
        var fixture = new ScriptedActivities(List.of(ScriptedActivities.reply("run_tests")));
        fixture.toolEntered = new CountDownLatch(1);
        fixture.toolRelease = new CountDownLatch(1);
        fixture.unconfirmedStops = 2;
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker("coding-runner-cancel-test");
            worker.registerWorkflowImplementationTypes(CodingWorkflowImpl.class);
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
            worker.registerWorkflowImplementationTypes(CodingWorkflowImpl.class);
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

    @Test void deadlineRequestsRunnerStopWhileToolActivityIsBlocked() throws Exception {
        var fixture = new ScriptedActivities(List.of(ScriptedActivities.reply("run_tests")));
        fixture.toolEntered = new CountDownLatch(1);
        fixture.toolRelease = new CountDownLatch(1);
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker("coding-runner-deadline-test");
            worker.registerWorkflowImplementationTypes(CodingWorkflowImpl.class);
            worker.registerActivitiesImplementations(fixture);
            environment.start();
            var workflow = environment.getWorkflowClient().newWorkflowStub(CodingWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId(RUN).setTaskQueue("coding-runner-deadline-test").build());
            var limits = new Limits(40, 100, 80, 2, 2, 180_000, 120_000, 180_000, 10_000, 100);
            var spec = new RunSpec(RUN, "start-1", "f".repeat(64), "task-tracker-v1",
                    "0".repeat(64), "evaluator-v1", "profile-1", "1".repeat(64), SEED, limits);
            var result = WorkflowClient.execute(workflow::run, spec);
            assertTrue(fixture.toolEntered.await(5, TimeUnit.SECONDS));

            assertEquals("EXPIRED", result.get(5, TimeUnit.SECONDS).status());
            assertEquals(RUN + ":turn-1:tool-1", fixture.cancelledRunner);
        }
    }

    @Test void queuedCancellationDoesNotEnterRunningAfterAdmissionUnblocks() throws Exception {
        var fixture = new ScriptedActivities();
        fixture.admissionEntered = new CountDownLatch(1);
        fixture.admissionRelease = new CountDownLatch(1);
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker("coding-queued-cancel-test");
            worker.registerWorkflowImplementationTypes(CodingWorkflowImpl.class);
            worker.registerActivitiesImplementations(fixture);
            environment.start();
            var workflow = environment.getWorkflowClient().newWorkflowStub(CodingWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId(RUN).setTaskQueue("coding-queued-cancel-test").build());
            var spec = new RunSpec(RUN, "start-1", "f".repeat(64), "task-tracker-v1",
                    "0".repeat(64), "evaluator-v1", "profile-1", "1".repeat(64), SEED,
                    Limits.codingDefaults());
            var result = WorkflowClient.execute(workflow::run, spec);
            assertTrue(fixture.admissionEntered.await(5, TimeUnit.SECONDS));

            assertEquals("APPLIED", workflow.cancel(new CancelCommand("cancel-queued", 0)).deliveryStatus());
            fixture.admissionRelease.countDown();

            assertEquals("CANCELLED", result.get(5, TimeUnit.SECONDS).status());
            assertFalse(fixture.publishedStatuses.contains("RUNNING"));
            assertTrue(fixture.modelAttempts.isEmpty());
        }
    }

    @Test void evaluatorInfrastructureFailureStopsWithoutAnotherModelTurn() {
        var fixture = new ScriptedActivities();
        fixture.infrastructureFailureOnFirstSubmit = true;
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker("coding-evaluator-failure-test");
            worker.registerWorkflowImplementationTypes(CodingWorkflowImpl.class);
            worker.registerActivitiesImplementations(fixture);
            environment.start();
            var workflow = environment.getWorkflowClient().newWorkflowStub(CodingWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId(RUN).setTaskQueue("coding-evaluator-failure-test").build());
            var spec = new RunSpec(RUN, "start-1", "f".repeat(64), "task-tracker-v1",
                    "0".repeat(64), "evaluator-v1", "profile-1", "1".repeat(64), SEED,
                    Limits.codingDefaults());

            assertEquals("INFRASTRUCTURE_FAILED", workflow.run(spec).status());
            assertEquals(1, fixture.submissions);
        }
    }

    @Test void toolActivityFailureBecomesVisibleInfrastructureTerminalState() {
        var fixture = new ScriptedActivities(List.of(ScriptedActivities.reply("run_tests")));
        fixture.toolActivityFailure = true;
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker("coding-tool-failure-test");
            worker.registerWorkflowImplementationTypes(CodingWorkflowImpl.class);
            worker.registerActivitiesImplementations(fixture);
            environment.start();
            var workflow = environment.getWorkflowClient().newWorkflowStub(CodingWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId(RUN).setTaskQueue("coding-tool-failure-test").build());
            var spec = new RunSpec(RUN, "start-1", "f".repeat(64), "task-tracker-v1",
                    "0".repeat(64), "evaluator-v1", "profile-1", "1".repeat(64), SEED,
                    Limits.codingDefaults());

            assertEquals("INFRASTRUCTURE_FAILED", workflow.run(spec).status());
            assertTrue(fixture.released);
        }
    }

    private static final class ScriptedActivities implements RunActivities {
        private final List<ModelReply> replies;
        private final List<String> toolSnapshots = new ArrayList<>();
        private final List<String> publishedStatuses = new ArrayList<>();
        private int modelCalls;
        private boolean unknownFirstCall;
        private boolean contextOverflowFirstCall;
        private final List<Integer> modelAttempts = new ArrayList<>();
        private CountDownLatch modelEntered;
        private CountDownLatch modelRelease;
        private CountDownLatch toolEntered;
        private CountDownLatch toolRelease;
        private CountDownLatch admissionEntered;
        private CountDownLatch admissionRelease;
        private String cancelledRunner;
        private long modelDelayMillis;
        private boolean infrastructureFailureOnFirstSubmit;
        private boolean toolActivityFailure;
        private int submissions;
        private boolean released;
        private int unconfirmedStops;
        private int stopRequests;

        private ScriptedActivities() {
            this(List.of(reply("read_file"), reply("write_file"), reply("run_tests"),
                    reply("write_file", "submit"), reply("write_file", "submit")));
        }

        private ScriptedActivities(List<ModelReply> replies) { this.replies = replies; }

        @Override public boolean acquireAdmission(String runId) {
            if (admissionEntered != null) {
                admissionEntered.countDown();
                try {
                    if (!admissionRelease.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("test admission release timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return true;
        }
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
            if (modelEntered != null) {
                modelEntered.countDown();
                try {
                    if (!modelRelease.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            modelAttempts.add(call.attempt());
            if (unknownFirstCall) {
                unknownFirstCall = false;
                throw ApplicationFailure.newNonRetryableFailure("uncertain", "MODEL_ATTEMPT_UNKNOWN");
            }
            if (contextOverflowFirstCall) {
                contextOverflowFirstCall = false;
                throw ApplicationFailure.newNonRetryableFailure("context overflow", "MODEL_CONTEXT_OVERFLOW");
            }
            return replies.get(modelCalls++);
        }
        @Override public ToolOutcome invokeTool(ToolInvocation call) {
            if (toolActivityFailure)
                throw ApplicationFailure.newNonRetryableFailure("runner unavailable", "RUNNER_INFRASTRUCTURE_FAILED");
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
            if (submit && infrastructureFailureOnFirstSubmit)
                return new ToolOutcome(call.invocationId(), call.request().providerCallId(), name,
                        snapshot, "{\"status\":\"INFRASTRUCTURE_FAILED\"}", true, false,
                        "EVALUATOR_INFRASTRUCTURE_FAILED");
            return new ToolOutcome(call.invocationId(), call.request().providerCallId(), name,
                    snapshot, "{\"status\":\"OK\"}", submit, passed);
        }
        @Override public String appendConversation(String previousBlobId, ModelReply reply,
                List<ToolOutcome> outcomes) { return "2".repeat(64); }
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
