package com.brianmehrman.harness.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.benchmark.Evaluator;
import com.brianmehrman.harness.model.ModelCall;
import com.brianmehrman.harness.model.ModelReply;
import com.brianmehrman.harness.model.ProfileRevision;
import com.brianmehrman.harness.model.ProfileRevisionStore;
import com.brianmehrman.harness.model.ScriptedModelAdapter;
import com.brianmehrman.harness.model.ToolRequest;
import com.brianmehrman.harness.runner.Runner;
import com.brianmehrman.harness.runs.BlobStore;
import com.brianmehrman.harness.runs.RunCommandService;
import com.brianmehrman.harness.runs.RunProjectionRepository;
import com.brianmehrman.harness.runs.StartCommand;
import com.brianmehrman.harness.workspace.WorkspaceStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;
import io.temporal.failure.ApplicationFailure;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("runner")
class RunActivitiesImplIT {
    @Autowired RunCommandService commands;
    @Autowired ProfileRevisionStore profiles;
    @Autowired BlobStore blobs;
    @Autowired WorkspaceStore workspaces;
    @Autowired Runner runner;
    @Autowired Evaluator evaluator;
    @Autowired ModelAttemptStore attempts;
    @Autowired RunAdmissionStore admissions;
    @Autowired RunProjectionRepository projections;

    @Test void activityRedeliveryReturnsSavedModelReplyWithoutASecondCall() {
        String profile = "profile-" + UUID.randomUUID();
        profiles.save(new ProfileRevision(profile, "ollama", "http://127.0.0.1:11434", "fixture-local",
                "a".repeat(64), 8192, 1024, 0, true, null));
        var start = commands.start(new StartCommand("start-" + UUID.randomUUID(),
                "task-tracker-v1", profile, Limits.codingDefaults()));
        var spec = JsonMapper.builder().build().readValue(
                // The stored workflow input, not a reconstructed fixture, supplies the seed.
                storedSpec(start.runId()), RunSpec.class);
        var scripted = new ScriptedModelAdapter(List.of(new ModelReply("inspect", List.of(
                new ToolRequest("provider-1", "read_file", "{\"path\":\"REQUIREMENTS.md\"}")),
                12L, 8L, "tool_calls")));
        var activities = new RunActivitiesImpl(blobs, profiles, workspaces, runner, evaluator,
                attempts, admissions, projections, revision -> scripted);
        String conversation = activities.startConversation(spec);
        var request = new ModelCall(start.runId(), "model-1", 1, profile, conversation,
                System.currentTimeMillis() + 10_000);

        ModelReply first = activities.callModel(request);
        ModelReply replay = activities.callModel(request);

        assertEquals(first, replay);
        assertEquals(1, scripted.callCount());
        assertEquals("read_file", replay.tools().getFirst().name());
        String continued = activities.appendConversation(conversation, replay, List.of(
                new ToolOutcome("tool-1", "provider-1", "read_file",
                        new com.brianmehrman.harness.workspace.SnapshotRef(spec.seedSnapshotSha256()),
                        "{\"status\":\"OK\"}", false, false)));
        var messages = JsonMapper.builder().build().readTree(blobs.get(continued)).path("messages");
        assertEquals("assistant", messages.get(messages.size() - 2).path("role").asText());
        assertEquals("provider-1", messages.get(messages.size() - 2)
                .path("toolCalls").get(0).path("id").asText());
        assertEquals("provider-1", messages.get(messages.size() - 1).path("id").asText());
    }

    @Test void providerFailureRecordsUnknownWithoutAnAutomaticSecondCall() {
        String profile = "profile-" + UUID.randomUUID();
        profiles.save(new ProfileRevision(profile, "ollama", "http://127.0.0.1:11434", "fixture-local",
                "a".repeat(64), 8192, 1024, 0, true, null));
        var start = commands.start(new StartCommand("start-" + UUID.randomUUID(),
                "task-tracker-v1", profile, Limits.codingDefaults()));
        var spec = JsonMapper.builder().build().readValue(storedSpec(start.runId()), RunSpec.class);
        var activities = new RunActivitiesImpl(blobs, profiles, workspaces, runner, evaluator,
                attempts, admissions, projections, revision -> request -> {
                    throw new IllegalStateException("injected transport failure");
                });
        var call = new ModelCall(start.runId(), "model-failure", 1, profile,
                activities.startConversation(spec), System.currentTimeMillis() + 10_000);

        var failure = assertThrows(ApplicationFailure.class, () -> activities.callModel(call));
        assertEquals("MODEL_ATTEMPT_UNKNOWN", failure.getType());
        assertEquals("UNKNOWN", jdbc.queryForObject("select status from model_attempt where run_id=? and invocation_id=?",
                String.class, start.runId(), "model-failure"));
    }

    @Test void contextOverflowIsReportedAsAValidationFailure() {
        String profile = "profile-" + UUID.randomUUID();
        profiles.save(new ProfileRevision(profile, "ollama", "http://127.0.0.1:11434", "fixture-local",
                "a".repeat(64), 8192, 1024, 0, true, null));
        var start = commands.start(new StartCommand("start-" + UUID.randomUUID(),
                "task-tracker-v1", profile, Limits.codingDefaults()));
        var spec = JsonMapper.builder().build().readValue(storedSpec(start.runId()), RunSpec.class);
        var activities = new RunActivitiesImpl(blobs, profiles, workspaces, runner, evaluator,
                attempts, admissions, projections, revision -> request -> {
                    throw new IllegalArgumentException("MODEL_CONTEXT_OVERFLOW");
                });
        var call = new ModelCall(start.runId(), "model-overflow", 1, profile,
                activities.startConversation(spec), System.currentTimeMillis() + 10_000);

        var failure = assertThrows(ApplicationFailure.class, () -> activities.callModel(call));
        assertEquals("MODEL_CONTEXT_OVERFLOW", failure.getType());
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    private String storedSpec(String runId) {
        return jdbc.queryForObject("select spec_json::text from run_request where run_id=?",
                String.class, runId);
    }
}
