package com.brianmehrman.harness.api;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.execution.CodingWorkflowImpl;
import com.brianmehrman.harness.execution.Limits;
import com.brianmehrman.harness.execution.RunActivities;
import com.brianmehrman.harness.execution.RunSpec;
import com.brianmehrman.harness.execution.ToolInvocation;
import com.brianmehrman.harness.execution.ToolOutcome;
import com.brianmehrman.harness.model.ModelCall;
import com.brianmehrman.harness.model.ModelReply;
import com.brianmehrman.harness.model.ProfileRevision;
import com.brianmehrman.harness.model.ProfileRevisionStore;
import com.brianmehrman.harness.runs.BlobStore;
import com.brianmehrman.harness.runs.RunEvent;
import com.brianmehrman.harness.runs.RunProjectionRepository;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.WorkerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "harness.api.dispatch-interval-ms=100")
@ActiveProfiles("api")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiWorkflowIT {
    private static final String QUEUE = "api-it-" + UUID.randomUUID();
    @DynamicPropertySource static void queue(DynamicPropertyRegistry registry) {
        registry.add("harness.temporal.task-queue", () -> QUEUE);
    }

    @LocalServerPort int port;
    @Autowired WorkflowClient client;
    @Autowired ProfileRevisionStore profiles;
    @Autowired BlobStore blobs;
    @Autowired RunProjectionRepository projections;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void acceptedHttpStartIsDeliveredToScriptedTemporalWorkflow() throws Exception {
        var factory = WorkerFactory.newInstance(client);
        var worker = factory.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(CodingWorkflowImpl.class);
        worker.registerActivitiesImplementations(new ScriptedActivities(blobs, projections));
        factory.start();
        try {
            String profile = "api-scripted-" + UUID.randomUUID();
            profiles.save(new ProfileRevision(profile, "ollama", "http://127.0.0.1:11434", "fixture-local",
                    "a".repeat(64), 8192, 1024, 0, true, null));
            String command = "api-workflow-start-" + UUID.randomUUID();
            String body = json.writeValueAsString(Map.of("commandId", command,
                    "benchmarkVersion", "task-tracker-v1", "profileRevision", profile,
                    "limits", Limits.codingDefaults().withModelTurns(2)));
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/runs"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var http = HttpClient.newHttpClient();
            var accepted = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(202, accepted.statusCode());
            String run = json.readTree(accepted.body()).path("runId").asText();
            JsonNode detail = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                var get = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/runs/" + run)).GET().build();
                detail = json.readTree(http.send(get, HttpResponse.BodyHandlers.ofString()).body());
                if ("FAILED".equals(detail.path("projection").path("status").asText()) &&
                        detail.path("projection").path("lastSequence").asLong() > 0 &&
                        !detail.path("projection").path("stale").asBoolean()) break;
                Thread.sleep(100);
            }
            assertNotNull(detail);
            assertEquals("FAILED", detail.path("projection").path("status").asText());
            assertFalse(detail.path("projection").path("stale").asBoolean());
            assertEquals("DELIVERED", detail.path("commands").get(0).path("deliveryStatus").asText());
            assertEquals("STARTED", detail.path("commands").get(0)
                    .path("applicationOutcome").path("outcome").asText());
            var eventRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port +
                    "/api/runs/" + run + "/events?after=0")).GET().build();
            JsonNode events = json.readTree(http.send(eventRequest, HttpResponse.BodyHandlers.ofString()).body());
            assertTrue(events.size() > 0);
            String digest = events.get(0).path("payloadBlobId").asText();
            var artifactRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port +
                    "/api/runs/" + run + "/artifacts/" + digest)).GET().build();
            var artifact = http.send(artifactRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, artifact.statusCode());
            assertTrue(json.readTree(artifact.body()).path("status").isTextual());
            assertEquals(run, json.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body())
                    .path("runId").asText());
        } finally {
            factory.shutdownNow();
            factory.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    static final class ScriptedActivities implements RunActivities {
        private final BlobStore blobs;
        private final RunProjectionRepository projections;
        private final JsonMapper json = JsonMapper.builder().build();
        ScriptedActivities(BlobStore blobs, RunProjectionRepository projections) {
            this.blobs = blobs;
            this.projections = projections;
        }
        @Override public boolean acquireAdmission(String runId) { return true; }
        @Override public void releaseAdmission(String runId) {}
        @Override public String startConversation(RunSpec spec) { return "b".repeat(64); }
        @Override public ModelReply callModel(ModelCall call) {
            return new ModelReply("scripted response", List.of(), 1L, 1L, "stop");
        }
        @Override public ToolOutcome invokeTool(ToolInvocation call) { throw new AssertionError("No tool requested"); }
        @Override public String appendConversation(String previousBlobId, ModelReply reply,
                List<ToolOutcome> outcomes) { return previousBlobId; }
        @Override public void publish(String runId, long sequence, long version, String status,
                String snapshotSha256, String terminalResultBlobId) {
            String digest = blobs.put("application/vnd.harness.run-event+json", json.writeValueAsBytes(
                    Map.of("version", version, "status", status, "snapshotSha256", snapshotSha256)));
            projections.append(new RunEvent(runId, sequence, runId + ":event-" + sequence,
                    "STATE_CHANGED", digest));
        }
        @Override public boolean cancelRunner(String invocationId) { return true; }
    }
}
