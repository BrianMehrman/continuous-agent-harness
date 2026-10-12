package com.brianmehrman.harness.recovery;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/** Scans real loopback operator responses with a sentinel stored in credential metadata. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "harness.api.dispatch-enabled=false")
@ActiveProfiles("api")
@ExtendWith(OutputCaptureExtension.class)
class SecretRedactionIT {
    @LocalServerPort int port;
    @Autowired ProfileRevisionStore profiles;
    @Autowired BlobStore blobs;
    @Autowired RunProjectionRepository projections;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void credentialReferenceStaysOutOfRunProfileEventAndArtifactResponses(CapturedOutput output) throws Exception {
        String sentinel = "task9-sentinel-credential-" + UUID.randomUUID();
        String profile = "task9-secret-profile-" + UUID.randomUUID();
        profiles.save(new ProfileRevision(profile, "ollama", "http://127.0.0.1:11434", "fixture-local",
                "a".repeat(64), 8192, 1024, 0, true, sentinel));
        String body = json.writeValueAsString(Map.of("commandId", "task9-secret-start-" + UUID.randomUUID(),
                "benchmarkVersion", "task-tracker-v1", "profileRevision", profile,
                "limits", Limits.codingDefaults()));
        var created = request("POST", "/api/runs", body);
        assertThat(created.statusCode()).isEqualTo(202);
        String runId = json.readTree(created.body()).path("runId").asText();
        String payload = blobs.put("application/vnd.harness.run-event+json",
                json.writeValueAsBytes(Map.of("version", 1, "status", "RUNNING")));
        projections.append(new RunEvent(runId, 1, runId + ":secret-scan", "STATE_CHANGED", payload));

        assertClean(created, sentinel);
        for (String path : new String[]{"/api/profiles", "/api/runs", "/api/runs/" + runId,
                "/api/runs/" + runId + "/events?after=0",
                "/api/runs/" + runId + "/artifacts/" + payload}) {
            var response = request("GET", path, null);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertClean(response, sentinel);
        }
        assertThat(output.getAll()).doesNotContain(sentinel);
    }

    private void assertClean(HttpResponse<String> response, String sentinel) {
        assertThat(response.body()).doesNotContain(sentinel);
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (body == null) builder.GET();
        else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
