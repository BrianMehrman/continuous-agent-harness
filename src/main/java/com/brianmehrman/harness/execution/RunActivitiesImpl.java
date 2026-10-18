package com.brianmehrman.harness.execution;

import com.brianmehrman.harness.benchmark.Evaluator;
import com.brianmehrman.harness.model.ModelCall;
import com.brianmehrman.harness.model.ModelReply;
import com.brianmehrman.harness.model.ProfileRevisionStore;
import com.brianmehrman.harness.runner.Runner;
import com.brianmehrman.harness.runs.BlobStore;
import com.brianmehrman.harness.runs.RunEvent;
import com.brianmehrman.harness.runs.RunProjectionRepository;
import com.brianmehrman.harness.runs.ReviewArtifactService;
import com.brianmehrman.harness.workspace.SnapshotHasher;
import com.brianmehrman.harness.workspace.SnapshotRef;
import com.brianmehrman.harness.workspace.WorkspaceStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import io.temporal.failure.ApplicationFailure;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Durable I/O effects for one workflow; external calls are separated from Workflow replay. */
@Component
@Profile("worker")
public class RunActivitiesImpl implements RunActivities {
    private static final int MAX_CONVERSATION_BYTES = 4 * 1024 * 1024;
    private static final String CONVERSATION = "application/vnd.harness.conversation+json";
    private static final String PROTOCOL = """

            Protocol:
            - Every reply must call at least one tool.
            - Make at most one write_file per reply and keep each file under 150 lines. A reply longer than the output limit is lost whole, so split a large class or test suite into several files.
            - Read a file before overwriting it, and pass the sha256 from your latest read_file or write_file result as expectedSha256 ("absent" for a new file).
            - After writing, run build and fix the reported errors, then run run_tests; submit only after both pass.
            - In tests, check error handling through the command-line entry point or the exception a method throws; never call a method with arguments its signature does not accept, because that cannot compile.
            - Trust the "changed" field of a write_file result; do not reread a file to check whether a write took effect.
            - Start each reply's text with two short lines, before any tool call: "Progress: <what is done>" and "Next: <your next step>".
            - Older turns are compacted into a "Working state" message. Treat it as authoritative and reread files when you need their contents.""";
    private final BlobStore blobs;
    private final ProfileRevisionStore profiles;
    private final WorkspaceStore workspaces;
    private final Runner runner;
    private final ModelAttemptStore attempts;
    private final RunAdmissionStore admissions;
    private final RunProjectionRepository projections;
    private final ModelAdapterFactory adapters;
    private final ToolRouter tools;
    private final RunTelemetry telemetry;
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public RunActivitiesImpl(BlobStore blobs, ProfileRevisionStore profiles, WorkspaceStore workspaces,
            Runner runner, Evaluator evaluator, ModelAttemptStore attempts, RunAdmissionStore admissions,
            RunProjectionRepository projections, ModelAdapterFactory adapters, RunTelemetry telemetry,
            JdbcTemplate jdbc, ReviewArtifactService reviews) {
        this.blobs = Objects.requireNonNull(blobs);
        this.profiles = Objects.requireNonNull(profiles);
        this.workspaces = Objects.requireNonNull(workspaces);
        this.runner = Objects.requireNonNull(runner);
        this.attempts = Objects.requireNonNull(attempts);
        this.admissions = Objects.requireNonNull(admissions);
        this.projections = Objects.requireNonNull(projections);
        this.adapters = Objects.requireNonNull(adapters);
        this.tools = new ToolRouter(workspaces, runner, Objects.requireNonNull(evaluator), blobs, jdbc, reviews);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override public boolean acquireAdmission(String runId) { return admissions.acquire(runId); }

    @Override public void releaseAdmission(String runId) {
        admissions.releaseAfterTerminalTransition(runId);
    }

    @Override public String startConversation(RunSpec spec) {
        var profile = profiles.get(spec.profileRevision());
        String system;
        String task;
        if ("repository-v1".equals(spec.benchmarkVersion())) {
            var item = jdbc.queryForMap("select title,instructions_blob_id,base_commit from repository_work_item where run_id=?", spec.runId());
            String instructions = new String(blobs.get((String) item.get("instructions_blob_id")), StandardCharsets.UTF_8);
            system = "Edit the frozen repository snapshot using the six provided tools. " +
                    "Read relevant files, make a focused change, run checks, then submit a concise summary. " +
                    "A failed check must be reported honestly. Submit means ready for human review; changes are not applied to the live repository." +
                    PROTOCOL;
            task = "Task: " + item.get("title") + "\nBase commit: " + item.get("base_commit") + "\nInstructions:\n" + instructions;
        } else {
            task = workspaces.files(spec.runId(), new SnapshotRef(spec.seedSnapshotSha256())).get("REQUIREMENTS.md");
            if (task == null) throw new IllegalStateException("Benchmark requirements are missing");
            system = "Implement the Java task-tracker CLI described in REQUIREMENTS.md using the six provided tools. " +
                    "Use list_files and read_file to inspect the starter before changing it. " +
                    "A passing submit is required; the host evaluates the submitted snapshot." + PROTOCOL;
        }
        var memory = WorkingMemory.start(spec.runId(), system, task, profile.contextTokens(), profile.outputTokens());
        return storeConversation(WorkingMemory.write(memory));
    }

    @Override public ModelReply callModel(ModelCall call) {
        String requestJson = json.writeValueAsString(call);
        String requestBlob = blobs.put("application/vnd.harness.model-request+json",
                requestJson.getBytes(StandardCharsets.UTF_8));
        String input = SnapshotHasher.content("model-call-v1\n" + requestJson);
        var lease = attempts.begin(call.runId(), call.invocationId(), call.attempt(), input, requestBlob);
        if (lease.outcome() == ModelAttemptStore.Outcome.RECORDED)
            return json.readValue(blobs.get(lease.responseBlobId()), ModelReply.class);
        if (lease.outcome() == ModelAttemptStore.Outcome.UNKNOWN)
            throw ApplicationFailure.newNonRetryableFailure("Model attempt has an unknown outcome", "MODEL_ATTEMPT_UNKNOWN");
        String status = projections.view(call.runId()).status();
        if (System.currentTimeMillis() >= call.deadlineEpochMillis() ||
                "CANCEL_REQUESTED".equals(status) || "STOPPING".equals(status) ||
                "CANCELLED".equals(status) || "EXPIRED".equals(status) ||
                "SUCCEEDED".equals(status) || "READY_FOR_REVIEW".equals(status) || "FAILED".equals(status) ||
                "LIMIT_EXHAUSTED".equals(status) || "INFRASTRUCTURE_FAILED".equals(status) ||
                "CONTEXT_OVERFLOW".equals(status)) {
            attempts.abandon(lease);
            throw ApplicationFailure.newNonRetryableFailure(
                    "Model attempt was not dispatched before its run state or deadline changed",
                    "MODEL_ATTEMPT_UNKNOWN");
        }
        try (var attempt = telemetry.modelAttempt(call)) {
            ModelCall dispatched = call;
            byte[] conversation = blobs.get(call.conversationBlobId());
            if (WorkingMemory.isV2(conversation)) {
                var memory = WorkingMemory.read(conversation);
                if (!WorkingMemory.fits(memory)) throw new IllegalArgumentException("MODEL_CONTEXT_OVERFLOW");
                String rendered = blobs.put(CONVERSATION, json.writeValueAsBytes(WorkingMemory.render(memory)));
                dispatched = new ModelCall(call.runId(), call.invocationId(), call.attempt(),
                        call.profileRevision(), rendered, call.deadlineEpochMillis());
            }
            ModelReply reply = adapters.forProfile(profiles.get(call.profileRevision())).call(dispatched);
            String responseBlob = blobs.put("application/vnd.harness.model-reply+json", json.writeValueAsBytes(reply));
            if (!attempts.complete(lease, responseBlob))
                throw ApplicationFailure.newNonRetryableFailure("Model attempt lost ownership", "MODEL_ATTEMPT_UNKNOWN");
            attempt.succeeded();
            return reply;
        } catch (RuntimeException failure) {
            attempts.abandon(lease);
            if (failure instanceof IllegalArgumentException &&
                    failure.getMessage() != null && failure.getMessage().contains("MODEL_CONTEXT_OVERFLOW"))
                throw ApplicationFailure.newNonRetryableFailureWithCause(
                        "Model context exceeds the selected profile", "MODEL_CONTEXT_OVERFLOW", failure);
            throw ApplicationFailure.newNonRetryableFailureWithCause(
                    "Model attempt ended without a response receipt", "MODEL_ATTEMPT_UNKNOWN", failure);
        }
    }

    @Override public ToolOutcome invokeTool(ToolInvocation call) {
        try (var attempt = telemetry.toolAttempt(call)) {
            ToolOutcome outcome = tools.route(call);
            if (outcome.failureCode() == null) attempt.succeeded();
            return outcome;
        }
    }

    @Override public String appendConversation(String previousBlobId, ModelReply reply,
            List<ToolOutcome> outcomes) {
        byte[] previous = blobs.get(previousBlobId);
        if (WorkingMemory.isV2(previous))
            return storeConversation(WorkingMemory.write(WorkingMemory.fold(WorkingMemory.read(previous), reply, outcomes)));
        Map<String, Object> root = json.readValue(previous, new TypeReference<>() {});
        if (!Integer.valueOf(1).equals(root.get("version")) || !(root.get("messages") instanceof List<?> old))
            throw new IllegalArgumentException("Invalid conversation");
        var messages = new ArrayList<Object>(old);
        var calls = new ArrayList<Map<String, String>>();
        for (var request : reply.tools())
            calls.add(Map.of("id", request.providerCallId(), "name", request.name(),
                    "argumentsJson", request.argumentsJson()));
        var assistant = new LinkedHashMap<String, Object>();
        assistant.put("role", "assistant");
        assistant.put("content", reply.text() == null ? "" : reply.text());
        assistant.put("toolCalls", calls);
        messages.add(assistant);
        if (outcomes.size() != calls.size()) throw new IllegalArgumentException("Tool result count mismatch");
        for (int i = 0; i < outcomes.size(); i++) {
            var outcome = outcomes.get(i);
            var request = reply.tools().get(i);
            if (!request.providerCallId().equals(outcome.providerCallId()) ||
                    !request.name().equals(outcome.name()))
                throw new IllegalArgumentException("Tool result does not match request");
            messages.add(Map.of("role", "tool", "id", outcome.providerCallId(),
                    "name", outcome.name(), "content", outcome.responseJson()));
        }
        if (outcomes.isEmpty())
            messages.add(Map.of("role", "user", "content",
                    "Use the provided tools and submit the immutable snapshot. Text alone cannot complete this task."));
        return conversationBlob(Map.of("version", 1, "messages", messages));
    }

    @Override public void publish(String runId, long sequence, long version, String status,
            String snapshotSha256, String terminalResultBlobId) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("version", version);
        payload.put("status", status);
        payload.put("snapshotSha256", snapshotSha256);
        payload.put("terminalResultBlobId", terminalResultBlobId);
        String blob = blobs.put("application/vnd.harness.run-event+json", json.writeValueAsBytes(payload));
        projections.append(new RunEvent(runId, sequence, runId + ":event-" + sequence,
                "STATE_CHANGED", blob));
        telemetry.transition(runId, status);
    }

    @Override public void publishProgress(String runId, long sequence, long version, String status,
            String snapshotSha256, String type, String stage, String summary, String artifactDigest) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("version", version);
        payload.put("status", status);
        payload.put("snapshotSha256", snapshotSha256);
        payload.put("stage", stage);
        payload.put("summary", summary);
        payload.put("artifactDigest", artifactDigest);
        String blob = blobs.put("application/vnd.harness.run-event+json", json.writeValueAsBytes(payload));
        projections.append(new RunEvent(runId, sequence, runId + ":event-" + sequence, type, blob));
    }

    @Override public boolean cancelRunner(String invocationId) {
        runner.cancel(invocationId);
        return runner.stopConfirmed(invocationId);
    }

    private String conversationBlob(Object value) { return storeConversation(json.writeValueAsBytes(value)); }

    private String storeConversation(byte[] data) {
        if (data.length > MAX_CONVERSATION_BYTES)
            throw new IllegalArgumentException("MODEL_CONTEXT_OVERFLOW");
        return blobs.put(CONVERSATION, data);
    }
}
