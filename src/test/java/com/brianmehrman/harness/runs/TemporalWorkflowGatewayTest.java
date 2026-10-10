package com.brianmehrman.harness.runs;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.execution.CodingWorkflowImpl;
import com.brianmehrman.harness.execution.CodingWorkflow;
import com.brianmehrman.harness.execution.CancelCommand;
import com.brianmehrman.harness.execution.RunView;
import com.brianmehrman.harness.execution.Limits;
import com.brianmehrman.harness.execution.RunActivities;
import com.brianmehrman.harness.execution.RunSpec;
import com.brianmehrman.harness.execution.ToolInvocation;
import com.brianmehrman.harness.execution.ToolOutcome;
import com.brianmehrman.harness.model.ModelCall;
import com.brianmehrman.harness.model.ModelReply;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.client.WorkflowStub;
import java.util.List;
import org.junit.jupiter.api.Test;

class TemporalWorkflowGatewayTest {
    @Test void existingWorkflowKeepsItsOriginalInputIdentity() {
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker("coding-gateway-test");
            worker.registerWorkflowImplementationTypes(CodingWorkflowImpl.class);
            worker.registerActivitiesImplementations(new ImmediateActivities());
            environment.start();
            var gateway = new TemporalWorkflowGateway(environment.getWorkflowClient(), "coding-gateway-test");
            String runId = "run-" + "a".repeat(64);
            var spec = new RunSpec(runId, "start-1", "b".repeat(64), "task-tracker-v1",
                    "c".repeat(64), "evaluator-v1", "profile-1", "d".repeat(64),
                    "e".repeat(64), Limits.codingDefaults());

            assertEquals(WorkflowGateway.StartOutcome.STARTED, gateway.start(spec).outcome());
            assertEquals(WorkflowGateway.StartOutcome.ALREADY_EXISTS, gateway.start(spec).outcome());
            assertEquals(spec.inputSha256(), gateway.describe(runId).inputSha256());
            var changed = new RunSpec(runId, "start-1", "f".repeat(64), "task-tracker-v1",
                    spec.definitionSha256(), spec.evaluatorVersion(), spec.profileRevision(),
                    spec.modelDigest(), spec.seedSnapshotSha256(), spec.limits());
            assertEquals(spec.inputSha256(), gateway.start(changed).inputSha256());
            RunView terminal = WorkflowStub.fromTyped(environment.getWorkflowClient()
                    .newWorkflowStub(CodingWorkflow.class, runId)).getResult(RunView.class);
            assertEquals("FAILED", terminal.status());
            assertEquals("REJECTED_TERMINAL", gateway.cancel(runId,
                    new CancelCommand("cancel-1", terminal.version())).deliveryStatus());
        }
    }

    private static final class ImmediateActivities implements RunActivities {
        @Override public boolean acquireAdmission(String runId) { return true; }
        @Override public void releaseAdmission(String runId) {}
        @Override public String startConversation(RunSpec spec) { return "f".repeat(64); }
        @Override public ModelReply callModel(ModelCall call) { return new ModelReply("done", List.of(), 1L, 1L, "stop"); }
        @Override public ToolOutcome invokeTool(ToolInvocation call) { throw new AssertionError(); }
        @Override public String appendConversation(String previousBlobId, ModelReply reply,
                List<ToolOutcome> outcomes) { return previousBlobId; }
        @Override public void publish(String runId, long sequence, long version, String status,
                String snapshotSha256, String terminalResultBlobId) {}
        @Override public boolean cancelRunner(String invocationId) { throw new AssertionError(); }
    }
}
