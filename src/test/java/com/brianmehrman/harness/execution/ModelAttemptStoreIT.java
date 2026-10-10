package com.brianmehrman.harness.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.model.ProfileRevision;
import com.brianmehrman.harness.model.ProfileRevisionStore;
import com.brianmehrman.harness.runs.RunCommandService;
import com.brianmehrman.harness.runs.StartCommand;
import com.brianmehrman.harness.runs.BlobStore;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("runner")
class ModelAttemptStoreIT {
    @Autowired RunCommandService commands;
    @Autowired ProfileRevisionStore profiles;
    @Autowired ModelAttemptStore attempts;
    @Autowired BlobStore blobs;
    @Autowired JdbcTemplate jdbc;

    private String run() {
        String profile = "profile-" + UUID.randomUUID();
        profiles.save(new ProfileRevision(profile, "ollama", "http://127.0.0.1:11434", "fixture-local",
                "a".repeat(64), 8192, 1024, 0, true, null));
        return commands.start(new StartCommand("start-" + UUID.randomUUID(),
                "task-tracker-v1", profile, Limits.codingDefaults())).runId();
    }

    @Test void recordedResponseIsReusedWithoutClaimingAnotherProviderCall() {
        String runId = run();
        String request = blobs.put("application/json", "request".getBytes(StandardCharsets.UTF_8));
        String response = blobs.put("application/json", "response".getBytes(StandardCharsets.UTF_8));
        var first = attempts.begin(runId, "model-1", 1, "b".repeat(64), request);
        assertEquals(ModelAttemptStore.Outcome.STARTED, first.outcome());
        assertTrue(attempts.complete(first, response));

        var replay = attempts.begin(runId, "model-1", 1, "b".repeat(64), request);
        assertEquals(ModelAttemptStore.Outcome.RECORDED, replay.outcome());
        assertEquals(response, replay.responseBlobId());
        assertEquals(1, jdbc.queryForObject("select count(*) from model_attempt where run_id=?",
                Integer.class, runId));
    }

    @Test void unreceiptedRedeliveryBecomesUnknownAndFencesLateResponse() {
        String runId = run();
        String request = blobs.put("application/json", "request".getBytes(StandardCharsets.UTF_8));
        String response = blobs.put("application/json", "response".getBytes(StandardCharsets.UTF_8));
        var first = attempts.begin(runId, "model-1", 1, "b".repeat(64), request);
        var replay = attempts.begin(runId, "model-1", 1, "b".repeat(64), request);
        assertEquals(ModelAttemptStore.Outcome.UNKNOWN, replay.outcome());
        assertFalse(attempts.complete(first, response));
        var second = attempts.begin(runId, "model-1", 2, "b".repeat(64), request);
        assertEquals(ModelAttemptStore.Outcome.STARTED, second.outcome());
        assertThrows(IllegalArgumentException.class,
                () -> attempts.begin(runId, "model-1", 2, "e".repeat(64), request));
    }

    @Test void abandonedOwnerCannotWriteALateResponse() {
        String runId = run();
        String request = blobs.put("application/json", "request".getBytes(StandardCharsets.UTF_8));
        String response = blobs.put("application/json", "response".getBytes(StandardCharsets.UTF_8));
        var lease = attempts.begin(runId, "model-1", 1, "b".repeat(64), request);

        assertTrue(attempts.abandon(lease));
        assertFalse(attempts.complete(lease, response));
        assertEquals(ModelAttemptStore.Outcome.UNKNOWN,
                attempts.begin(runId, "model-1", 1, "b".repeat(64), request).outcome());
    }
}
