package com.brianmehrman.harness.execution;

import com.brianmehrman.harness.benchmark.Evaluator;
import com.brianmehrman.harness.model.ModelCall;
import com.brianmehrman.harness.model.ModelReply;
import com.brianmehrman.harness.model.ProfileRevisionStore;
import com.brianmehrman.harness.runner.Runner;
import com.brianmehrman.harness.runs.BlobStore;
import com.brianmehrman.harness.runs.RunEvent;
import com.brianmehrman.harness.runs.RunProjectionRepository;
import com.brianmehrman.harness.workspace.SnapshotHasher;
import com.brianmehrman.harness.workspace.SnapshotRef;
import com.brianmehrman.harness.workspace.WorkspaceStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final BlobStore blobs;
    private final ProfileRevisionStore profiles;
    private final WorkspaceStore workspaces;
    private final Runner runner;
    private final ModelAttemptStore attempts;
    private final RunAdmissionStore admissions;
    private final RunProjectionRepository projections;
    private final ModelAdapterFactory adapters;
    private final ToolRouter tools;
    private final JsonMapper json = JsonMapper.builder().build();

    public RunActivitiesImpl(BlobStore blobs, ProfileRevisionStore profiles, WorkspaceStore workspaces,
            Runner runner, Evaluator evaluator, ModelAttemptStore attempts, RunAdmissionStore admissions,
            RunProjectionRepository projections, ModelAdapterFactory adapters) {
        this.blobs = Objects.requireNonNull(blobs);
        this.profiles = Objects.requireNonNull(profiles);
        this.workspaces = Objects.requireNonNull(workspaces);
        this.runner = Objects.requireNonNull(runner);
        this.attempts = Objects.requireNonNull(attempts);
        this.admissions = Objects.requireNonNull(admissions);
        this.projections = Objects.requireNonNull(projections);
        this.adapters = Objects.requireNonNull(adapters);
        this.tools = new ToolRouter(workspaces, runner, Objects.requireNonNull(evaluator), blobs);
    }

    @Override public boolean acquireAdmission(String runId) { return admissions.acquire(runId); }

    @Override public void releaseAdmission(String runId) {
        admissions.releaseAfterTerminalTransition(runId);
    }

    @Override public String startConversation(RunSpec spec) {
        String requirements = workspaces.files(spec.runId(), new SnapshotRef(spec.seedSnapshotSha256()))
                .get("REQUIREMENTS.md");
        if (requirements == null) throw new IllegalStateException("Benchmark requirements are missing");
        var messages = List.of(
                Map.of("role", "system", "content", "Implement the Java task-tracker CLI using the six provided tools. A passing submit is required."),
                Map.of("role", "user", "content", requirements));
        return conversationBlob(Map.of("version", 1, "messages", messages));
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
        try {
            ModelReply reply = adapters.forProfile(profiles.get(call.profileRevision())).call(call);
            String responseBlob = blobs.put("application/vnd.harness.model-reply+json", json.writeValueAsBytes(reply));
            if (!attempts.complete(lease, responseBlob))
                throw ApplicationFailure.newNonRetryableFailure("Model attempt lost ownership", "MODEL_ATTEMPT_UNKNOWN");
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

    @Override public ToolOutcome invokeTool(ToolInvocation call) { return tools.route(call); }

    @Override public String appendConversation(String previousBlobId, ModelReply reply,
            List<ToolOutcome> outcomes) {
        Map<String, Object> root = json.readValue(blobs.get(previousBlobId), new TypeReference<>() {});
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
    }

    @Override public boolean cancelRunner(String invocationId) {
        runner.cancel(invocationId);
        return runner.stopConfirmed(invocationId);
    }

    private String conversationBlob(Object value) {
        byte[] data = json.writeValueAsBytes(value);
        if (data.length > MAX_CONVERSATION_BYTES)
            throw new IllegalArgumentException("MODEL_CONTEXT_OVERFLOW");
        return blobs.put("application/vnd.harness.conversation+json", data);
    }
}
