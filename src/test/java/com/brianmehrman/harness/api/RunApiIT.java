package com.brianmehrman.harness.api;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.execution.Limits;
import com.brianmehrman.harness.model.ProfileRevision;
import com.brianmehrman.harness.model.ProfileRevisionStore;
import com.brianmehrman.harness.runs.BlobStore;
import com.brianmehrman.harness.runs.RunEvent;
import com.brianmehrman.harness.runs.RunProjectionRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "harness.api.dispatch-enabled=false")
@ActiveProfiles("api")
class RunApiIT {
    @LocalServerPort int port;
    @Autowired ProfileRevisionStore profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired BlobStore blobs;
    @Autowired RunProjectionRepository projections;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void duplicateStartOrderedEventsAndVersionedCancel() throws Exception {
        String profile = "api-test-" + UUID.randomUUID();
        profiles.save(new ProfileRevision(profile, "ollama", "http://127.0.0.1:11434", "fixture-local",
                "a".repeat(64), 8192, 1024, 0, true, null));
        String commandId = "start-" + UUID.randomUUID();
        String body = json.writeValueAsString(Map.of("commandId", commandId,
                "benchmarkVersion", "task-tracker-v1", "profileRevision", profile,
                "limits", Limits.codingDefaults()));
        var first = request("POST", "/api/runs", body);
        assertEquals(202, first.statusCode());
        JsonNode created = json.readTree(first.body());
        String runId = created.path("runId").asText();
        assertTrue(runId.startsWith("run-"));
        assertEquals(commandId, created.path("commandId").asText());
        assertEquals("ACCEPTED", json.readTree(request("GET", "/api/runs/" + runId, null).body())
                .path("commands").get(0).path("deliveryStatus").asText());
        assertEquals(runId, json.readTree(request("POST", "/api/runs", body).body()).path("runId").asText());
        assertEquals(409, request("POST", "/api/runs", body.replace("task-tracker-v1", "other-v1")).statusCode());

        String one = event(runId, 1, 1, "RUNNING");
        String two = event(runId, 2, 2, "RUNNING");
        assertEquals(200, request("GET", "/api/runs/" + runId + "/events?after=0", null).statusCode());
        JsonNode page = json.readTree(request("GET", "/api/runs/" + runId + "/events?after=0", null).body());
        assertEquals(one, page.get(0).path("payloadBlobId").asText());
        assertEquals(two, page.get(1).path("payloadBlobId").asText());
        assertEquals(2, page.size());
        assertEquals(1, json.readTree(request("GET", "/api/runs/" + runId + "/events?after=1", null).body()).size());
        assertEquals(2, json.readTree(request("GET", "/api/runs/" + runId, null).body()).path("projection").path("lastSequence").asInt());
        String cancel = json.writeValueAsString(Map.of("commandId", "cancel-" + UUID.randomUUID(), "expectedVersion", 1));
        assertEquals(409, request("POST", "/api/runs/" + runId + "/cancel", cancel).statusCode());
        cancel = json.writeValueAsString(Map.of("commandId", "cancel-" + UUID.randomUUID(), "expectedVersion", 2));
        assertEquals(202, request("POST", "/api/runs/" + runId + "/cancel", cancel).statusCode());
        String resume = json.writeValueAsString(Map.of("commandId", "resume-" + UUID.randomUUID(),
                "expectedVersion", 2, "extraTurns", 5));
        assertEquals(409, request("POST", "/api/runs/" + runId + "/resume", resume).statusCode(), "Only a paused run resumes");
        typedEvent(runId, 3, 3, "PAUSED", "PAUSED", Map.of("reason", "TURN_LIMIT", "evidence", List.of(),
                "turnsUsed", 40, "turnsGranted", 40, "summary", "Paused: turn limit"));
        String tooMuch = json.writeValueAsString(Map.of("commandId", "resume-" + UUID.randomUUID(),
                "expectedVersion", 3, "extraTurns", 41));
        assertEquals(400, request("POST", "/api/runs/" + runId + "/resume", tooMuch).statusCode());
        String noExtension = json.writeValueAsString(Map.of("commandId", "resume-" + UUID.randomUUID(),
                "expectedVersion", 3, "extraTurns", 0, "extraToolCalls", 5));
        assertEquals(400, request("POST", "/api/runs/" + runId + "/resume", noExtension).statusCode(),
                "A resume must extend the limit that was reached");
        String resumeId = "resume-" + UUID.randomUUID();
        String good = json.writeValueAsString(Map.of("commandId", resumeId, "expectedVersion", 3,
                "extraTurns", 5, "hint", "Fix the definite-assignment error first"));
        assertEquals(202, request("POST", "/api/runs/" + runId + "/resume", good).statusCode());
        assertEquals(202, request("POST", "/api/runs/" + runId + "/resume", good).statusCode(), "Same command, same answer");
        assertEquals(409, request("POST", "/api/runs/" + runId + "/resume",
                good.replace("first", "second")).statusCode(), "A reused ID with different input conflicts");
        String stop = json.writeValueAsString(Map.of("commandId", "stop-" + UUID.randomUUID(), "expectedVersion", 3));
        assertEquals(202, request("POST", "/api/runs/" + runId + "/stop", stop).statusCode());
        JsonNode paused = json.readTree(request("GET", "/api/runs/" + runId, null).body());
        assertEquals("TURN_LIMIT", paused.path("pause").path("reason").asText());
        assertEquals("RESUME", paused.path("commands").get(2).path("kind").asText());
        jdbc.update("update run_command set delivered_at=now(),outcome_json='{}'::jsonb where command_id=?", commandId);
        JsonNode delivered = json.readTree(request("GET", "/api/runs/" + runId, null).body()).path("commands").get(0);
        assertEquals("DELIVERED", delivered.path("deliveryStatus").asText());
        assertTrue(delivered.path("applicationOutcome").isObject());
        JsonNode retried = json.readTree(request("POST", "/api/runs", body).body());
        assertEquals("DELIVERED", retried.path("deliveryStatus").asText());
        assertTrue(retried.path("applicationOutcome").isObject());
    }

    @Test void runScopedArtifactAndMalformedInputs() throws Exception {
        String profile = "api-test-" + UUID.randomUUID();
        profiles.save(new ProfileRevision(profile, "ollama", "http://127.0.0.1:11434", "fixture-local",
                "a".repeat(64), 8192, 1024, 0, true, null));
        String body = json.writeValueAsString(Map.of("commandId", "start-" + UUID.randomUUID(),
                "benchmarkVersion", "task-tracker-v1", "profileRevision", profile,
                "limits", Limits.codingDefaults()));
        String run = json.readTree(request("POST", "/api/runs", body).body()).path("runId").asText();
        String blob = event(run, 1, 1, "RUNNING");
        assertEquals(200, request("GET", "/api/runs/" + run + "/artifacts/" + blob, null).statusCode());
        String log = blobs.put("text/plain", "runner log".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String snapshot = jdbc.queryForObject("select snapshot_sha256 from run_projection where run_id=?",
                String.class, run);
        jdbc.update("insert into runner_invocation(invocation_id,run_id,input_sha256,snapshot_sha256," +
                        "operation,image_id,deadline_at,status,container_name,result_json) " +
                        "values (?,?,?,?,'BUILD',?,now()+interval '1 hour','SUCCEEDED',?,?::jsonb)",
                "api-log-" + UUID.randomUUID(), run, "f".repeat(64), snapshot,
                "sha256:" + "a".repeat(64), "api-log-container-" + UUID.randomUUID(),
                json.writeValueAsString(Map.of("logBlobId", log)));
        assertEquals("runner log", request("GET", "/api/runs/" + run + "/artifacts/" + log, null).body());
        String otherBody = body.replaceFirst("start-[0-9a-f-]+", "start-" + UUID.randomUUID());
        String otherRun = json.readTree(request("POST", "/api/runs", otherBody).body()).path("runId").asText();
        assertEquals(404, request("GET", "/api/runs/" + otherRun + "/artifacts/" + blob, null).statusCode());
        assertEquals(404, request("GET", "/api/runs/" + otherRun + "/artifacts/" + log, null).statusCode());
        assertEquals(404, request("GET", "/api/runs/" + run + "/artifacts/" + "f".repeat(64), null).statusCode());
        assertEquals(400, request("POST", "/api/runs", body.substring(0, body.length() - 1) + ",\"unexpected\":true}").statusCode());
        var crossOrigin = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/runs"))
                .header("Content-Type", "application/json").header("Origin", "https://foreign.example")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals(403, http.send(crossOrigin, HttpResponse.BodyHandlers.ofString()).statusCode());
        var form = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/runs"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("x=y")).build();
        assertEquals(415, http.send(form, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertFalse(request("GET", "/api/profiles", null).body().contains("credentialReference"));
    }

    private void typedEvent(String runId, long sequence, long version, String status, String type,
            Map<String, Object> detail) {
        var payload = new java.util.LinkedHashMap<String, Object>(detail);
        payload.put("version", version);
        payload.put("status", status);
        String digest = blobs.put("application/vnd.harness.run-event+json", json.writeValueAsBytes(payload));
        jdbc.update("insert into run_event(run_id,sequence,event_id,type,payload_blob_id) values (?,?,?,?,?)",
                runId, sequence, runId + ":api-test-" + sequence, type, digest);
        jdbc.update("update run_projection set version=?,status=?,last_sequence=? where run_id=?",
                version, status, sequence, runId);
    }

private String event(String runId, long sequence, long version, String status) {
        String digest = blobs.put("application/vnd.harness.run-event+json", json.writeValueAsBytes(
                Map.of("version", version, "status", status)));
        jdbc.update("insert into run_event(run_id,sequence,event_id,type,payload_blob_id) values (?,?,?,?,?)",
                runId, sequence, runId + ":api-test-" + sequence, "STATE_CHANGED", digest);
        jdbc.update("update run_projection set version=?,status=?,last_sequence=? where run_id=?",
                version, status, sequence, runId);
        return digest;
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
