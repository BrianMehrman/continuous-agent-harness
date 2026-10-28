package com.brianmehrman.harness.api;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.execution.Limits;
import com.brianmehrman.harness.model.OllamaProfile;
import com.brianmehrman.harness.model.AnthropicProfile;
import com.brianmehrman.harness.model.Prices;
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
        profiles.save(new OllamaProfile(profile, "ollama", "http://127.0.0.1:11434", "fixture-local",
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
        profiles.save(new OllamaProfile(profile, "ollama", "http://127.0.0.1:11434", "fixture-local",
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

    @Test void costPauseResumeRequiresEnoughAddedBudget() throws Exception {
        String profile = "api-cost-" + UUID.randomUUID();
        profiles.save(new AnthropicProfile(profile, "anthropic", "https://api.anthropic.com",
                "claude-haiku-5-5", "test_reference", 16384, 4096, 100000,
                new Prices(100000, 500000, 125000, 10000), true));
        JsonNode listed = json.readTree(request("GET", "/api/profiles", null).body());
        assertTrue(listed.isArray());
        assertTrue(listed.findValues("boundToDeveloper").stream().anyMatch(JsonNode::isBoolean));
        String start = json.writeValueAsString(Map.of("commandId", "start-cost-" + UUID.randomUUID(),
                "benchmarkVersion", "task-tracker-v1", "profileRevision", profile,
                "limits", Limits.codingDefaults(), "allowRemote", true,
                "costLimitMicros", 100000, "costBufferPercent", 10));
        var accepted = request("POST", "/api/runs", start);
        assertEquals(202, accepted.statusCode(), accepted.body());
        String runId = json.readTree(accepted.body()).path("runId").asText();
        JsonNode detail = json.readTree(request("GET", "/api/runs/" + runId, null).body());
        assertEquals("developer", detail.path("role").asText());
        assertEquals("claude-haiku-5-5", detail.path("model").asText());
        String receipt = blobs.put("application/json", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("insert into model_attempt(run_id,invocation_id,attempt,input_sha256,status," +
                        "request_blob_id,response_blob_id,ended_at,cost_micros,cost_estimated) " +
                        "values (?,?,1,?,'RECORDED',?,?,now(),90000,false)",
                runId, "cost-spent", "a".repeat(64), receipt, receipt);
        typedEvent(runId, 1, 1, "PAUSED", "PAUSED", Map.of("reason", "COST_LIMIT"));

        for (long extra : List.of(0L, 1000L)) {
            String body = json.writeValueAsString(Map.of("commandId", "resume-cost-" + UUID.randomUUID(),
                    "expectedVersion", 1, "extraCostMicros", extra));
            assertEquals(400, request("POST", "/api/runs/" + runId + "/resume", body).statusCode());
        }
        String enough = json.writeValueAsString(Map.of("commandId", "resume-cost-" + UUID.randomUUID(),
                "expectedVersion", 1, "extraCostMicros", 5000));
        assertEquals(202, request("POST", "/api/runs/" + runId + "/resume", enough).statusCode());
        typedEvent(runId, 2, 2, "PAUSED", "PAUSED", Map.of("reason", "TURN_LIMIT"));
        String overflow = json.writeValueAsString(Map.of("commandId", "resume-cost-" + UUID.randomUUID(),
                "expectedVersion", 2, "extraTurns", 1, "extraCostMicros", Long.MAX_VALUE));
        assertEquals(400, request("POST", "/api/runs/" + runId + "/resume", overflow).statusCode());
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
