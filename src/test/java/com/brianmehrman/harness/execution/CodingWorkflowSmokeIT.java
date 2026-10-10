package com.brianmehrman.harness.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.model.ModelCall;
import com.brianmehrman.harness.model.ModelReply;
import com.brianmehrman.harness.model.ToolRequest;
import com.brianmehrman.harness.runs.TemporalWorkflowGateway;
import com.brianmehrman.harness.runs.WorkflowGateway;
import com.brianmehrman.harness.workspace.SnapshotRef;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CodingWorkflowSmokeIT {
    @Test void scriptedRepairLoopCompletesOnTheRealTemporalService() throws Exception {
        String target = System.getenv().getOrDefault("HARNESS_TEMPORAL_TARGET", "127.0.0.1:7233");
        var service = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
        var client = WorkflowClient.newInstance(service,
                WorkflowClientOptions.newBuilder().setNamespace("harness").build());
        String queue = "coding-smoke-" + UUID.randomUUID();
        var factory = WorkerFactory.newInstance(client);
        var fixture = new FixtureActivities();
        try {
            var worker = factory.newWorker(queue);
            worker.registerWorkflowImplementationTypes(CodingWorkflowImpl.class);
            worker.registerActivitiesImplementations(fixture);
            factory.start();
            String runId = "run-" + UUID.randomUUID().toString().replace("-", "") + "a".repeat(32);
            var spec = new RunSpec(runId, "start-smoke", "f".repeat(64), "task-tracker-v1",
                    "0".repeat(64), "evaluator-v1", "profile-scripted", "1".repeat(64),
                    FixtureActivities.digest("seed"), Limits.codingDefaults());
            var gateway = new TemporalWorkflowGateway(client, queue);

            assertEquals(WorkflowGateway.StartOutcome.STARTED, gateway.start(spec).outcome());
            RunView result = WorkflowStub.fromTyped(client.newWorkflowStub(CodingWorkflow.class, runId))
                    .getResult(30, TimeUnit.SECONDS, RunView.class);

            assertEquals("SUCCEEDED", result.status());
            assertEquals(FixtureActivities.digest("final"), result.snapshotSha256());
            assertEquals(2, fixture.submissionCount);
            assertEquals(7, fixture.nextStep);
            assertTrue(fixture.released);
        } finally {
            factory.shutdownNow();
            factory.awaitTermination(10, TimeUnit.SECONDS);
            service.shutdown();
        }
    }

    private static final class FixtureActivities implements RunActivities {
        private final JsonNode steps;
        private int turn;
        private int nextStep;
        private int submissionCount;
        private boolean released;

        private FixtureActivities() throws IOException {
            try (var stream = getClass().getResourceAsStream("/execution/fixtures/scripted-task-tracker.json")) {
                if (stream == null) throw new IOException("Missing scripted task-tracker fixture");
                steps = JsonMapper.builder().build().readTree(stream.readAllBytes()).path("steps");
            }
        }

        private static String digest(String label) {
            return switch (label) {
                case "seed" -> "b".repeat(64);
                case "broken" -> "c".repeat(64);
                case "repaired" -> "d".repeat(64);
                case "final" -> "e".repeat(64);
                default -> throw new IllegalArgumentException("Unknown fixture snapshot");
            };
        }

        @Override public boolean acquireAdmission(String runId) { return true; }
        @Override public void releaseAdmission(String runId) { released = true; }
        @Override public String startConversation(RunSpec spec) { return "2".repeat(64); }
        @Override public ModelReply callModel(ModelCall call) {
            turn++;
            var requests = new ArrayList<ToolRequest>();
            for (JsonNode step : steps) {
                if (step.path("turn").asInt() == turn)
                    requests.add(new ToolRequest("provider-" + requests.size(), step.path("name").asText(), "{}"));
            }
            return new ModelReply("", requests, 10L, 10L, "tool_calls");
        }
        @Override public ToolOutcome invokeTool(ToolInvocation call) {
            JsonNode step = steps.get(nextStep++);
            assertEquals(step.path("name").asText(), call.request().name());
            assertEquals(digest(step.path("from").asText()), call.snapshot().sha256());
            boolean submit = "submit".equals(call.request().name());
            if (submit) submissionCount++;
            boolean passed = step.path("passed").asBoolean();
            return new ToolOutcome(call.invocationId(), call.request().providerCallId(),
                    call.request().name(), new SnapshotRef(digest(step.path("to").asText())),
                    "{\"status\":\"OK\"}", submit, passed);
        }
        @Override public String appendConversation(String previousBlobId, ModelReply reply,
                List<ToolOutcome> outcomes) { return previousBlobId; }
        @Override public void publish(String runId, long sequence, long version, String status,
                String snapshotSha256, String terminalResultBlobId) {}
        @Override public boolean cancelRunner(String invocationId) { throw new AssertionError(); }
    }
}
