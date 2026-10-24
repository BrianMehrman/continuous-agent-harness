package com.brianmehrman.harness.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.benchmark.Evaluator;
import com.brianmehrman.harness.model.ModelReply;
import com.brianmehrman.harness.model.OllamaProfile;
import com.brianmehrman.harness.model.ProfileRevisionStore;
import com.brianmehrman.harness.model.ScriptedModelAdapter;
import com.brianmehrman.harness.model.ToolRequest;
import com.brianmehrman.harness.runner.Runner;
import com.brianmehrman.harness.runner.RunnerBoundary;
import com.brianmehrman.harness.runner.TestRunnerSupervisor;
import com.brianmehrman.harness.runs.BlobStore;
import com.brianmehrman.harness.runs.ReviewArtifactService;
import com.brianmehrman.harness.runs.RunProjectionRepository;
import com.brianmehrman.harness.workspace.WorkspaceStore;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.WorkerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Drives a scripted repository task through the real workflow, router, Docker runner, and review packaging. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "harness.api.dispatch-interval-ms=100")
@ActiveProfiles("api")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepositoryTaskIT {
    private static final String QUEUE = "repository-it-" + UUID.randomUUID();
    private static final String SCHEMA = "repository_it_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PROBE = "src/main/java/com/brianmehrman/harness/ReviewProbe.java";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("harness.temporal.task-queue", () -> QUEUE);
        String base = System.getenv().getOrDefault("HARNESS_DB_URL",
                "jdbc:postgresql://127.0.0.1:55432/harness");
        registry.add("spring.datasource.url", () -> base + (base.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
    }

    @LocalServerPort int port;
    @Autowired WorkflowClient client;
    @Autowired BlobStore blobs;
    @Autowired ProfileRevisionStore profiles;
    @Autowired WorkspaceStore workspaces;
    @Autowired Runner runner;
    @Autowired Evaluator evaluator;
    @Autowired ModelAttemptStore attempts;
    @Autowired RunAdmissionStore admissions;
    @Autowired RunProjectionRepository projections;
    @Autowired RunTelemetry telemetry;
    @Autowired ReviewArtifactService reviews;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectProvider<RunnerBoundary> boundary;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @AfterAll void removeTestSchema() { jdbc.execute("drop schema if exists " + SCHEMA + " cascade"); }

    @Test void scriptedEditWithFailedCheckIsReadyForReviewWithTheFailureVisible() throws Exception {
        String repositoryImage = System.getenv("HARNESS_REPOSITORY_RUNNER_IMAGE");
        assertTrue(repositoryImage != null && repositoryImage.startsWith("sha256:"),
                "Set HARNESS_REPOSITORY_RUNNER_IMAGE from runner/repository-image.lock");
        String broken = "package com.brianmehrman.harness;\n\nclass ReviewProbe {\n";
        var model = new ScriptedModelAdapter(List.of(
                reply("read_file", Map.of("path", "README.md")),
                reply("write_file", Map.of("path", PROBE, "expectedSha256", "absent", "content", broken)),
                reply("build", Map.of()),
                reply("submit", Map.of("summary", "Added ReviewProbe; all checks passed."))));
        var activities = new RunActivitiesImpl(blobs, profiles, workspaces, runner, evaluator, attempts,
                admissions, projections, profile -> model, telemetry, jdbc, reviews);
        var factory = WorkerFactory.newInstance(client);
        var worker = factory.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(UnversionedCodingWorkflow.class);
        worker.registerActivitiesImplementations(activities);
        factory.start();
        var supervisor = new TestRunnerSupervisor(jdbc, transactions, workspaces, boundary);
        byte[] liveReadme = Files.readAllBytes(Path.of("README.md"));
        try {
            String profile = "repository-scripted-" + UUID.randomUUID();
            profiles.save(new OllamaProfile(profile, "ollama", "http://127.0.0.1:11434", "fixture-local",
                    "a".repeat(64), 8192, 1024, 0, true, null));
            var accepted = send("POST", "/api/work", json.writeValueAsString(Map.of(
                    "commandId", "repository-it-" + UUID.randomUUID(), "title", "Add a review probe",
                    "instructions", "Add a ReviewProbe class.", "profileRevision", profile)));
            assertEquals(202, accepted.statusCode(), accepted.body());
            String workId = json.readTree(accepted.body()).path("workId").asText();

            JsonNode detail = null;
            long deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos();
            while (System.nanoTime() < deadline) {
                detail = json.readTree(send("GET", "/api/work/" + workId, null).body());
                if (!List.of("QUEUED", "RUNNING").contains(detail.path("status").asText())) break;
                Thread.sleep(250);
            }
            assertNotNull(detail);
            assertEquals("READY_FOR_REVIEW", detail.path("status").asText(), detail.toString());
            assertEquals(4, model.callCount());
            assertEquals("Added ReviewProbe; all checks passed.", detail.path("modelSummary").asText());
            JsonNode checks = detail.path("checks");
            assertEquals(1, checks.size(), checks.toString());
            assertEquals("FAILED", checks.get(0).path("status").asText(), checks.toString());
            assertNotEquals(0, checks.get(0).path("exitCode").asInt());
            assertTrue(checks.get(0).path("forFinalSnapshot").asBoolean(), "The failed build checked the submitted code");

            String diff = new String(blobs.get(detail.path("reviewBlobId").asText()), StandardCharsets.UTF_8);
            assertTrue(diff.contains("+class ReviewProbe {"), diff);
            assertEquals(1, diff.split("diff --git ", -1).length - 1, "Only the probe file should differ:\n" + diff);
            assertTrue(diff.contains(PROBE), diff);
            assertArrayEquals(liveReadme, Files.readAllBytes(Path.of("README.md")));
            assertFalse(Files.exists(Path.of(PROBE)), "The live checkout must not receive model edits");

            List<JsonNode> events = new ArrayList<>();
            for (JsonNode event : json.readTree(send("GET", "/api/work/" + workId + "/events?after=0", null).body()))
                events.add(event);
            for (int i = 0; i < events.size(); i++)
                assertEquals(i + 1, events.get(i).path("sequence").asLong(), "Events must be contiguous");
            var tools = events.stream().filter(e -> "TOOL_RECORDED".equals(e.path("type").asText()))
                    .map(e -> e.path("stage").asText() + ":" + e.path("summary").asText()).toList();
            assertEquals(List.of("tool:read_file result recorded", "tool:write_file result recorded",
                    "check:build result recorded", "submit:submit result recorded"), tools);
            assertEquals(4, events.stream().filter(e -> "MODEL_RECORDED".equals(e.path("type").asText())).count());
            assertEquals("review", events.getLast().path("stage").asText());

            var requests = jdbc.queryForList("select request_blob_id from model_attempt where run_id=? " +
                    "order by invocation_id, attempt", String.class, workId);
            assertEquals(4, requests.size());
            WorkingMemory.Conversation lastSeen = null;
            for (String request : requests) {
                String conversation = json.readTree(blobs.get(request)).path("conversationBlobId").asText();
                lastSeen = WorkingMemory.read(blobs.get(conversation));
                assertEquals(2, lastSeen.version());
                assertTrue(WorkingMemory.fits(lastSeen), "Every model call must stay within the frozen budget");
            }
            assertEquals(3, lastSeen.state().turn());
            assertNotNull(lastSeen.state().progress(), "M1 runs carry stall progress");
            assertEquals(WorkingMemory.Thresholds.defaults(), lastSeen.state().progress().thresholds(),
                    "Thresholds come from the frozen pause policy");
            assertTrue(lastSeen.state().files().stream().anyMatch(f -> f.path().equals(PROBE) && f.written()));
            assertEquals("build", lastSeen.state().lastCheck().tool());
            assertTrue(lastSeen.system().contains("Progress:"), "The protocol rules reach repository runs");
            assertTrue(lastSeen.system().contains("at most one write_file per reply"), "The output-size rule reaches repository runs");
            assertTrue(lastSeen.system().contains("never call a method with arguments its signature does not accept"),
                    "The testing rule reaches repository runs");

            long cursor = events.get(events.size() / 2).path("sequence").asLong();
            JsonNode later = json.readTree(send("GET", "/api/work/" + workId + "/events?after=" + cursor, null).body());
            assertEquals(events.size() - cursor, later.size());
            assertEquals(cursor + 1, later.get(0).path("sequence").asLong());

            long cleanup = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!Boolean.TRUE.equals(jdbc.queryForObject("select coalesce(bool_and(cleaned), true) " +
                    "from runner_invocation where run_id=?", Boolean.class, workId)) && System.nanoTime() < cleanup)
                Thread.sleep(250);
            assertEquals(Boolean.TRUE, jdbc.queryForObject("select bool_and(cleaned) from runner_invocation " +
                    "where run_id=?", Boolean.class, workId), "Runner containers must be removed");
        } finally {
            supervisor.close();
            factory.shutdownNow();
            factory.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private ModelReply reply(String tool, Map<String, String> arguments) {
        return new ModelReply("", List.of(new ToolRequest("call-" + tool, tool,
                json.writeValueAsString(arguments))), 10L, 10L, "tool_calls");
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json").method(method,
                HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
