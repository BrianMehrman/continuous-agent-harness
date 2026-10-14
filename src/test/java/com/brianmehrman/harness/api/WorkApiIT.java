package com.brianmehrman.harness.api;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.model.ProfileRevision;
import com.brianmehrman.harness.model.ProfileRevisionStore;
import com.brianmehrman.harness.execution.RunSpec;
import com.brianmehrman.harness.execution.RunAdmissionStore;
import com.brianmehrman.harness.runs.RepositoryWorkService;
import com.brianmehrman.harness.runs.ReviewArtifactService;
import com.brianmehrman.harness.runs.BlobStore;
import com.brianmehrman.harness.runs.CommandDispatcher;
import com.brianmehrman.harness.workspace.WorkspaceStore;
import com.brianmehrman.harness.workspace.WriteRequest;
import com.brianmehrman.harness.workspace.SnapshotHasher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "harness.api.dispatch-enabled=false")
@ActiveProfiles("api")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkApiIT {
    private static final String SCHEMA = "work_it_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String base = System.getenv().getOrDefault("HARNESS_DB_URL",
                "jdbc:postgresql://127.0.0.1:55432/harness");
        registry.add("spring.datasource.url", () -> base + (base.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("harness.temporal.task-queue", () -> "work-it-" + SCHEMA);
    }
    @LocalServerPort int port;
    @Autowired CommandDispatcher dispatcher;
    @Autowired ProfileRevisionStore profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired RunAdmissionStore admission;
    @Autowired RepositoryWorkService work;
    @Autowired ReviewArtifactService reviews;
    @Autowired WorkspaceStore workspace;
    @Autowired BlobStore blobs;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @AfterAll void removeTestSchema() { jdbc.execute("drop schema if exists " + SCHEMA + " cascade"); }

    @Test void acceptsFreeTextWorkIdempotentlyAndCancelsQueuedItem() throws Exception {
        String profile = "work-test-" + UUID.randomUUID();
        profiles.save(new ProfileRevision(profile, "ollama", "http://127.0.0.1:11434", "fixture-local",
                "a".repeat(64), 8192, 1024, 0, true, null));
        String firstCommand = "work-" + UUID.randomUUID();
        String secondCommand = "work-" + UUID.randomUUID();
        String firstBody = json.writeValueAsString(Map.of("commandId", firstCommand,
                "title", "First change", "instructions", "Update a Java source file", "profileRevision", profile));
        String secondBody = json.writeValueAsString(Map.of("commandId", secondCommand,
                "title", "Second change", "instructions", "Update the README", "profileRevision", profile));

        var first = request("POST", "/api/work", firstBody);
        assertEquals(202, first.statusCode(), first.body());
        String firstId = json.readTree(first.body()).path("workId").asText();
        String frozenSpec = jdbc.queryForObject("select spec_json::text from run_request where run_id=?",
                String.class, firstId);
        assertEquals("repository-v1", json.readValue(frozenSpec, RunSpec.class).benchmarkVersion());
        assertEquals(firstId, json.readTree(request("POST", "/api/work", firstBody).body())
                .path("workId").asText());
        jdbc.update("update run_command set delivered_at=now() where command_id=?", firstCommand);
        assertEquals("DELIVERED", json.readTree(request("POST", "/api/work", firstBody).body())
                .path("deliveryStatus").asText());
        assertEquals(409, request("POST", "/api/work",
                firstBody.replace("First change", "Different change")).statusCode());
        var second = request("POST", "/api/work", secondBody);
        assertEquals(202, second.statusCode(), second.body());
        String secondId = json.readTree(second.body()).path("workId").asText();
        JsonNode list = json.readTree(request("GET", "/api/work", null).body());
        int firstPosition = position(list.path("queue"), firstId);
        int secondPosition = position(list.path("queue"), secondId);
        assertTrue(firstPosition > 0);
        assertEquals(firstPosition + 1, secondPosition);
        assertEquals("QUEUED", json.readTree(request("GET", "/api/work/" + secondId, null).body())
                .path("status").asText());
        String cancel = json.writeValueAsString(Map.of("commandId", "cancel-" + UUID.randomUUID(),
                "expectedVersion", 0));
        assertEquals(202, request("POST", "/api/work/" + secondId + "/cancel", cancel).statusCode());
        assertEquals(202, request("POST", "/api/work/" + secondId + "/cancel", cancel).statusCode());
        assertEquals("CANCELLED", json.readTree(request("GET", "/api/work/" + secondId, null).body())
                .path("status").asText());
        JsonNode after = json.readTree(request("GET", "/api/work", null).body());
        assertTrue(position(after.path("queue"), firstId) > 0);
        assertEquals(0, position(after.path("queue"), secondId));
        String deliveredCancelId = "cancel-delivered-" + UUID.randomUUID();
        assertEquals(202, request("POST", "/api/work/" + firstId + "/cancel",
                json.writeValueAsString(Map.of("commandId", deliveredCancelId, "expectedVersion", 0))).statusCode());
        assertEquals(1, jdbc.queryForObject("select count(*) from run_command where command_id=? and kind='CANCEL'",
                Integer.class, deliveredCancelId));
        jdbc.update("update run_projection set status='READY_FOR_REVIEW' where run_id=?", firstId);
    }

    @Test void locallyCancelledStartIsNeverDeliveredAndInFlightDeliveryRefusesCancel() throws Exception {
        String profile = "work-cancel-" + UUID.randomUUID();
        profiles.save(new ProfileRevision(profile, "ollama", "http://127.0.0.1:11434", "fixture-local",
                "a".repeat(64), 8192, 1024, 0, true, null));
        String startCommand = "work-cancel-" + UUID.randomUUID();
        String runId = create(startCommand, profile);
        jdbc.update("update run_command set lease_owner='other-dispatcher',lease_until=now()+interval '30 seconds' " +
                "where command_id=?", startCommand);
        String cancel = json.writeValueAsString(Map.of("commandId", "cancel-" + UUID.randomUUID(), "expectedVersion", 0));
        assertEquals(409, request("POST", "/api/work/" + runId + "/cancel", cancel).statusCode(),
                "A START that may be mid-delivery must not be cancelled locally");
        assertEquals("QUEUED", work.detail(runId).get("status"));
        jdbc.update("update run_command set lease_owner=null,lease_until=null where command_id=?", startCommand);

        assertEquals(202, request("POST", "/api/work/" + runId + "/cancel", cancel).statusCode());
        assertFalse(dispatcher.dispatchOnce(runId), "A locally cancelled START must not be delivered");
        assertNull(jdbc.queryForObject("select delivered_at from run_command where command_id=?",
                java.sql.Timestamp.class, startCommand));
        assertEquals("CANCELLED", work.detail(runId).get("status"));
    }

    @Test void admissionWaitsForOlderDeliveredWork() throws Exception {
        String profile = "work-order-" + UUID.randomUUID();
        profiles.save(new ProfileRevision(profile, "ollama", "http://127.0.0.1:11434", "fixture-local",
                "a".repeat(64), 8192, 1024, 0, true, null));
        String firstCommand = "work-order-" + UUID.randomUUID();
        String secondCommand = "work-order-" + UUID.randomUUID();
        String first = create(firstCommand, profile);
        String second = create(secondCommand, profile);
        jdbc.update("update run_command set delivered_at=now() where command_id in (?,?)",
                firstCommand, secondCommand);
        assertFalse(admission.acquire(second));
        assertTrue(admission.acquire(first));
        assertFalse(admission.acquire(second));
        jdbc.update("update run_projection set status='READY_FOR_REVIEW' where run_id=?", first);
        assertTrue(admission.releaseAfterTerminalTransition(first));
        assertTrue(admission.acquire(second));
        assertTrue(admission.releaseAfterTerminalTransition(second));
    }

    @Test void repositoryEditProducesIsolatedReviewDiffWithFailedCheckVisible() throws Exception {
        String profile = "work-review-" + UUID.randomUUID();
        profiles.save(new ProfileRevision(profile, "ollama", "http://127.0.0.1:11434", "fixture-local",
                "a".repeat(64), 8192, 1024, 0, true, null));
        String runId = create("work-review-" + UUID.randomUUID(), profile);
        String seed = jdbc.queryForObject("select seed_sha256 from repository_work_item where run_id=?", String.class, runId);
        var parent = new com.brianmehrman.harness.workspace.SnapshotRef(seed);
        var seedFiles = workspace.files(runId, parent);
        assertTrue(seedFiles.keySet().stream().noneMatch(path -> path.contains("/harness/benchmark/")),
                "Host-side evaluator code must not be model-visible");
        assertTrue(seedFiles.containsKey("README.md"));
        String original = seedFiles.get("README.md");
        byte[] live = Files.readAllBytes(Path.of("README.md"));
        String changed = original + "\nA review-only test edit.\n";
        var edited = workspace.write(new WriteRequest(runId, "write-review", parent, "README.md",
                SnapshotHasher.content(original), changed));
        jdbc.update("insert into runner_invocation(invocation_id,run_id,input_sha256,snapshot_sha256,operation," +
                        "image_id,deadline_at,status,container_name,result_json) values (?,?,?,?,'TEST',?,now()," +
                        "'FAILED',?,?::jsonb)", "check-review", runId, "f".repeat(64), edited.sha256(),
                "sha256:" + "a".repeat(64), "review-container-" + UUID.randomUUID(),
                "{\"exitCode\":1,\"uncertain\":false}");
        jdbc.update("insert into runner_invocation(invocation_id,run_id,input_sha256,snapshot_sha256,operation," +
                        "image_id,deadline_at,status,container_name,result_json) values (?,?,?,?,'BUILD',?,now()," +
                        "'SUCCEEDED',?,?::jsonb)", "check-earlier", runId, "e".repeat(64), seed,
                "sha256:" + "a".repeat(64), "review-container-" + UUID.randomUUID(),
                "{\"exitCode\":0,\"uncertain\":false}");
        var review = reviews.packageReview(runId, edited, "Changed the README; check failed.");
        assertEquals(review.diffBlobId(), reviews.packageReview(runId, edited,
                "Changed the README; check failed.").diffBlobId());
        String diff = new String(blobs.get(review.diffBlobId()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(diff.contains("+A review-only test edit."));
        assertFalse(diff.contains("diff --git a/src/"));
        assertTrue(diff.length() < 1024, "A small append should have a concise review diff");
        var reviewChecks = review.checks().stream().filter(check -> check.invocationId().equals("check-review")).toList();
        assertEquals(1, reviewChecks.size());
        assertEquals("FAILED", reviewChecks.getFirst().status());
        assertEquals(1, reviewChecks.getFirst().exitCode());
        @SuppressWarnings("unchecked")
        var detailChecks = (List<Map<String, Object>>)work.detail(runId).get("checks");
        var byId = new java.util.HashMap<Object, Object>();
        detailChecks.forEach(check -> byId.put(check.get("invocationId"), check.get("forFinalSnapshot")));
        assertEquals(Boolean.TRUE, byId.get("check-review"), "The failed check ran on the submitted snapshot");
        assertEquals(Boolean.FALSE, byId.get("check-earlier"), "A green check on an older snapshot must not read as current");
        assertArrayEquals(live, Files.readAllBytes(Path.of("README.md")));
        assertEquals(review.diffBlobId(), work.detail(runId).get("reviewBlobId"));

        String failedRun = create("work-failed-" + UUID.randomUUID(), profile);
        String failedSeed = jdbc.queryForObject("select seed_sha256 from repository_work_item where run_id=?",
                String.class, failedRun);
        String failedOriginal = workspace.files(failedRun, new com.brianmehrman.harness.workspace.SnapshotRef(failedSeed))
                .get("README.md");
        assertTrue(failedOriginal.endsWith("\n"));
        var noFinalNewline = workspace.write(new WriteRequest(failedRun, "write-newline",
                new com.brianmehrman.harness.workspace.SnapshotRef(failedSeed), "README.md",
                SnapshotHasher.content(failedOriginal), failedOriginal.substring(0, failedOriginal.length() - 1)));
        jdbc.update("update run_projection set status='FAILED',snapshot_sha256=? where run_id=?",
                noFinalNewline.sha256(), failedRun);
        var failedDetail = work.detail(failedRun);
        assertEquals(noFinalNewline.sha256(), failedDetail.get("finalSnapshotSha256"));
        String failedDiff = new String(blobs.get((String)failedDetail.get("reviewBlobId")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(failedDiff.contains("\\ No newline at end of file"));
        assertArrayEquals(live, Files.readAllBytes(Path.of("README.md")));
    }

    private String create(String commandId, String profile) throws Exception {
        String body = json.writeValueAsString(Map.of("commandId", commandId, "title", "Ordered task",
                "instructions", "Edit a Java class", "profileRevision", profile));
        var response = request("POST", "/api/work", body);
        assertEquals(202, response.statusCode(), response.body());
        return json.readTree(response.body()).path("workId").asText();
    }

    private static int position(JsonNode queue, String workId) {
        for (JsonNode item : queue) if (workId.equals(item.path("workId").asText()))
            return item.path("position").asInt();
        return 0;
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json").method(method,
                HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
