package com.brianmehrman.harness.execution;

import com.brianmehrman.harness.benchmark.Evaluator;
import com.brianmehrman.harness.runner.InvocationRequest;
import com.brianmehrman.harness.runner.InvocationResult;
import com.brianmehrman.harness.runner.Operation;
import com.brianmehrman.harness.runner.Runner;
import com.brianmehrman.harness.runs.BlobStore;
import com.brianmehrman.harness.workspace.SnapshotHasher;
import com.brianmehrman.harness.workspace.WorkspaceStore;
import com.brianmehrman.harness.workspace.WriteRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The only model-visible tool entry point; caller supplies all trusted identities. */
public final class ToolRouter {
    private final WorkspaceStore workspaces;
    private final Runner runner;
    private final Evaluator evaluator;
    private final BlobStore blobs;
    private final JsonMapper json = JsonMapper.builder().build();

    public ToolRouter(WorkspaceStore workspaces, Runner runner, Evaluator evaluator, BlobStore blobs) {
        this.workspaces = Objects.requireNonNull(workspaces);
        this.runner = Objects.requireNonNull(runner);
        this.evaluator = Objects.requireNonNull(evaluator);
        this.blobs = Objects.requireNonNull(blobs);
    }

    public ToolOutcome route(ToolInvocation call) {
        Objects.requireNonNull(call);
        String name = call.request().name();
        if (!List.of("list_files", "read_file", "write_file", "run_tests", "build", "submit").contains(name))
            return error(call, "UNKNOWN_TOOL");
        if (System.currentTimeMillis() >= call.deadlineEpochMillis()) return error(call, "DEADLINE_EXCEEDED");
        try {
            JsonNode args = json.readTree(call.request().argumentsJson());
            if (args == null || !args.isObject()) return error(call, "INVALID_ARGUMENTS");
            return switch (name) {
                case "list_files" -> list(call, args);
                case "read_file" -> read(call, args);
                case "write_file" -> write(call, args);
                case "run_tests" -> args.size() == 0 ? run(call, Operation.TEST) : error(call, "INVALID_ARGUMENTS");
                case "build" -> args.size() == 0 ? run(call, Operation.BUILD) : error(call, "INVALID_ARGUMENTS");
                case "submit" -> submit(call, args);
                default -> throw new IllegalStateException("Unexpected tool");
            };
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException error) {
            return error(call, "INVALID_ARGUMENTS");
        }
    }

    private ToolOutcome list(ToolInvocation call, JsonNode args) {
        if (args.size() > 1 || args.has("prefix") && !args.path("prefix").isTextual())
            return error(call, "INVALID_ARGUMENTS");
        String prefix = args.path("prefix").asText("");
        if (prefix.length() > 1024 || prefix.contains("..") || prefix.contains("\\") ||
                prefix.codePoints().anyMatch(Character::isISOControl)) return error(call, "INVALID_ARGUMENTS");
        var paths = workspaces.files(call.runId(), call.snapshot()).keySet().stream()
                .filter(path -> path.startsWith(prefix)).toList();
        return ok(call, call.snapshot(), Map.of("status", "OK", "paths", paths), false, false);
    }

    private ToolOutcome read(ToolInvocation call, JsonNode args) {
        if (args.size() != 1 || !args.path("path").isTextual()) return error(call, "INVALID_ARGUMENTS");
        String path = args.path("path").asText();
        if (path.startsWith("/") || path.contains("..") || path.contains("\\") ||
                path.codePoints().anyMatch(Character::isISOControl)) return error(call, "INVALID_ARGUMENTS");
        String content = workspaces.files(call.runId(), call.snapshot()).get(path);
        if (content == null) return error(call, "FILE_NOT_FOUND");
        return ok(call, call.snapshot(), Map.of("status", "OK", "content", content,
                "sha256", SnapshotHasher.content(content)), false, false);
    }

    private ToolOutcome write(ToolInvocation call, JsonNode args) {
        if (args.size() != 3 || !args.path("path").isTextual() || !args.path("expectedSha256").isTextual() ||
                !args.path("content").isTextual()) return error(call, "INVALID_ARGUMENTS");
        var result = workspaces.write(new WriteRequest(call.runId(), call.invocationId(), call.snapshot(),
                args.path("path").asText(), args.path("expectedSha256").asText(), args.path("content").asText()));
        return ok(call, result, Map.of("status", "OK", "snapshotSha256", result.sha256()), false, false);
    }

    private ToolOutcome run(ToolInvocation call, Operation operation) {
        runner.ensureStarted(new InvocationRequest(call.runId(), call.invocationId(), call.snapshot(),
                operation, call.deadlineEpochMillis()));
        Optional<InvocationResult> result;
        while ((result = runner.result(call.invocationId())).isEmpty()) {
            if (System.currentTimeMillis() >= call.deadlineEpochMillis()) return error(call, "RUNNER_DEADLINE_EXCEEDED");
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return error(call, "RUNNER_INTERRUPTED");
            }
        }
        InvocationResult receipt = result.get();
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("status", receipt.status());
        data.put("exitCode", receipt.exitCode());
        data.put("logBlobId", receipt.logBlobId());
        String log = receipt.logBlobId() == null ? "" : new String(blobs.get(receipt.logBlobId()), StandardCharsets.UTF_8);
        boolean shortened = log.length() > 16_384;
        data.put("log", shortened ? log.substring(0, 16_384) : log);
        data.put("truncated", receipt.truncated() || shortened);
        data.put("artifactBlobId", receipt.artifactBlobId());
        data.put("uncertain", receipt.uncertain());
        boolean infrastructure = receipt.uncertain() || "UNCERTAIN".equals(receipt.status());
        return ok(call, call.snapshot(), data, false, false,
                infrastructure ? "RUNNER_INFRASTRUCTURE_FAILED" : null);
    }

    private ToolOutcome submit(ToolInvocation call, JsonNode args) {
        if (args.size() != 1 || !args.path("summary").isTextual() ||
                args.path("summary").asText().isBlank() || args.path("summary").asText().length() > 1000)
            return error(call, "INVALID_ARGUMENTS");
        long seed = Integer.toUnsignedLong(call.runId().hashCode());
        var verdict = evaluator.evaluate(call.runId(), call.invocationId(), call.snapshot(), seed,
                call.deadlineEpochMillis());
        var response = new java.util.LinkedHashMap<String, Object>();
        response.put("status", verdict.outcome().name());
        response.put("passed", verdict.passed());
        response.put("failedCases", verdict.failedCases());
        response.put("artifactSha256", verdict.artifactSha256());
        return ok(call, call.snapshot(), response, true, verdict.passed(),
                verdict.outcome() == com.brianmehrman.harness.benchmark.Evaluation.Outcome.INFRASTRUCTURE_FAILED
                        ? "EVALUATOR_INFRASTRUCTURE_FAILED" : null);
    }

    private ToolOutcome ok(ToolInvocation call, com.brianmehrman.harness.workspace.SnapshotRef snapshot,
            Map<String, ?> content, boolean attemptedSubmission, boolean passed) {
        return ok(call, snapshot, content, attemptedSubmission, passed, null);
    }

    private ToolOutcome ok(ToolInvocation call, com.brianmehrman.harness.workspace.SnapshotRef snapshot,
            Map<String, ?> content, boolean attemptedSubmission, boolean passed, String failureCode) {
        return new ToolOutcome(call.invocationId(), call.request().providerCallId(), call.request().name(),
                snapshot, json.writeValueAsString(content), attemptedSubmission, passed, failureCode);
    }

    private ToolOutcome error(ToolInvocation call, String code) {
        return new ToolOutcome(call.invocationId(), call.request().providerCallId(), call.request().name(),
                call.snapshot(), json.writeValueAsString(Map.of("status", "ERROR", "code", code)), false, false);
    }
}
