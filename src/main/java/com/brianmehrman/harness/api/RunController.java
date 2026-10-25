package com.brianmehrman.harness.api;

import com.brianmehrman.harness.execution.CancelCommand;
import com.brianmehrman.harness.execution.Limits;
import com.brianmehrman.harness.execution.ResumeCommand;
import com.brianmehrman.harness.execution.StopCommand;
import com.brianmehrman.harness.execution.CommandResult;
import com.brianmehrman.harness.runs.RunCommandService;
import com.brianmehrman.harness.runs.RunQueryService;
import com.brianmehrman.harness.runs.StartCommand;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@RestController
@Profile("api")
public class RunController {
    private static final Set<String> START = Set.of("commandId", "benchmarkVersion", "limits");
    private static final Set<String> START_OPTIONAL = Set.of("profileRevision", "role", "allowRemote",
            "costLimitMicros", "costBufferPercent");
    private static final Set<String> CANCEL = Set.of("commandId", "expectedVersion");
    private static final Set<String> RESUME_REQUIRED = Set.of("commandId", "expectedVersion");
    private static final Set<String> RESUME_OPTIONAL = Set.of("extraTurns", "extraToolCalls", "extraMinutes",
            "extraCostMicros", "hint");
    private static final Set<String> LIMITS = Set.of("modelTurns", "toolCalls", "modelAttempts",
            "perTurnModelAttempts", "runnerAttempts", "modelAttemptMillis", "buildTestMillis",
            "evaluationMillis", "fileOperationMillis", "runMillis");
    private final RunCommandService commands;
    private final RunQueryService queries;
    private final JsonMapper json = JsonMapper.builder().build();

    public RunController(RunCommandService commands, RunQueryService queries) {
        this.commands = commands;
        this.queries = queries;
    }

    @PostMapping(value = "/api/runs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> start(@RequestBody String body) {
        Map<String, Object> data = object(body, START, START_OPTIONAL);
        if (!(data.get("limits") instanceof Map<?, ?> raw) || !raw.keySet().equals(LIMITS))
            throw new BadInput("Full limits object is required");
        Limits limits;
        try { limits = json.readValue(json.writeValueAsString(raw), Limits.class); }
        catch (RuntimeException invalid) { throw new BadInput("Invalid limits"); }
        try {
            var accepted = commands.start(new StartCommand(
                    string(data, "commandId"), string(data, "benchmarkVersion"),
                    optionalString(data, "profileRevision"), limits, optionalString(data, "role"),
                    optionalBoolean(data, "allowRemote"), optionalLong(data, "costLimitMicros"),
                    optionalInteger(data, "costBufferPercent")));
            var state = queries.command(accepted.runId(), accepted.commandId());
            return ResponseEntity.accepted().body(new CommandResponse(accepted.runId(), accepted.commandId(),
                    state.deliveryStatus(), state.applicationOutcome()));
        } catch (IllegalArgumentException invalid) { throw classify(invalid); }
    }

    @GetMapping("/api/runs") public Object list() { return queries.list(); }

    @GetMapping("/api/runs/{runId}") public Object detail(@PathVariable String runId) {
        try { return queries.detail(runId); }
        catch (IllegalArgumentException missing) { throw classify(missing); }
    }

    @GetMapping("/api/runs/{runId}/events")
    public Object events(@PathVariable String runId, @RequestParam(defaultValue = "0") long after) {
        try { return queries.events(runId, after); }
        catch (IllegalArgumentException invalid) { throw classify(invalid); }
    }

    @PostMapping(value = "/api/runs/{runId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> cancel(@PathVariable String runId, @RequestBody String body) {
        Map<String, Object> data = object(body, CANCEL);
        if (!(data.get("expectedVersion") instanceof Number number) ||
                !(number instanceof Integer || number instanceof Long)) throw new BadInput("Invalid expectedVersion");
        CancelCommand command;
        try { command = new CancelCommand(string(data, "commandId"), number.longValue()); }
        catch (IllegalArgumentException invalid) { throw new BadInput(invalid.getMessage()); }
        try {
            if (!queries.commandExists(runId, command.commandId()) &&
                    command.expectedVersion() != queries.currentVersion(runId))
                throw new Conflict("Expected version differs from current run version");
            var accepted = commands.cancel(runId, command);
            var state = queries.command(accepted.runId(), accepted.commandId());
            return ResponseEntity.accepted().body(new CommandResponse(accepted.runId(), accepted.commandId(),
                    state.deliveryStatus(), state.applicationOutcome()));
        } catch (IllegalArgumentException invalid) { throw classify(invalid); }
    }

    @PostMapping(value = "/api/runs/{runId}/resume", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> resume(@PathVariable String runId, @RequestBody String body) {
        ResumeCommand command = resumeCommand(body);
        return accepted(runId, command.commandId(), command.expectedVersion(), () -> commands.resume(runId, command));
    }

    @PostMapping(value = "/api/runs/{runId}/stop", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> stop(@PathVariable String runId, @RequestBody String body) {
        StopCommand command = stopCommand(body);
        return accepted(runId, command.commandId(), command.expectedVersion(), () -> commands.stop(runId, command));
    }

    static StopCommand stopCommand(String body) {
        Map<String, Object> data = object(body, CANCEL);
        try { return new StopCommand(string(data, "commandId"), version(data)); }
        catch (IllegalArgumentException invalid) { throw new BadInput(invalid.getMessage()); }
    }

    static ResumeCommand resumeCommand(String body) {
        Map<String, Object> data = object(body, RESUME_REQUIRED, RESUME_OPTIONAL);
        try {
            Long extraCost = optionalLong(data, "extraCostMicros");
            return new ResumeCommand(string(data, "commandId"), version(data), count(data, "extraTurns"),
                    count(data, "extraToolCalls"), count(data, "extraMinutes"),
                    data.get("hint") == null ? null : string(data, "hint"), extraCost == null ? 0 : extraCost);
        } catch (IllegalArgumentException invalid) { throw new BadInput(invalid.getMessage()); }
    }

    private ResponseEntity<?> accepted(String runId, String commandId, long expectedVersion,
            java.util.function.Supplier<CommandResult> accept) {
        try {
            if (!queries.commandExists(runId, commandId) && expectedVersion != queries.currentVersion(runId))
                throw new Conflict("Expected version differs from current run version");
            var accepted = accept.get();
            var state = queries.command(accepted.runId(), accepted.commandId());
            return ResponseEntity.accepted().body(new CommandResponse(accepted.runId(), accepted.commandId(),
                    state.deliveryStatus(), state.applicationOutcome()));
        } catch (IllegalArgumentException invalid) { throw classify(invalid); }
    }

    @GetMapping("/api/runs/{runId}/artifacts/{digest}")
    public ResponseEntity<byte[]> artifact(@PathVariable String runId, @PathVariable String digest) {
        try {
            var artifact = queries.artifact(runId, digest);
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, artifact.mediaType())
                    .header("X-Content-Type-Options", "nosniff")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + digest + "\"")
                    .body(artifact.content());
        } catch (IllegalArgumentException invalid) { throw classify(invalid); }
    }

    static Map<String, Object> object(String body, Set<String> fields) {
        try {
            Map<String, Object> data = JsonMapper.builder().build().readValue(body, new TypeReference<>() {});
            if (data == null || !data.keySet().equals(fields)) throw new BadInput("Unexpected or missing fields");
            return data;
        } catch (BadInput invalid) { throw invalid; }
        catch (RuntimeException invalid) { throw new BadInput("Malformed JSON object"); }
    }

    static Map<String, Object> object(String body, Set<String> required, Set<String> optional) {
        try {
            Map<String, Object> data = JsonMapper.builder().build().readValue(body, new TypeReference<>() {});
            if (data == null || !data.keySet().containsAll(required) ||
                    !data.keySet().stream().allMatch(k -> required.contains(k) || optional.contains(k)))
                throw new BadInput("Unexpected or missing fields");
            return data;
        } catch (BadInput invalid) { throw invalid; }
        catch (RuntimeException invalid) { throw new BadInput("Malformed JSON object"); }
    }

    static long version(Map<String, Object> data) {
        if (!(data.get("expectedVersion") instanceof Number number) ||
                !(number instanceof Integer || number instanceof Long)) throw new BadInput("Invalid expectedVersion");
        return number.longValue();
    }

    static int count(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (value == null) return 0;
        if (!(value instanceof Integer number) || number < 0) throw new BadInput("Invalid " + field);
        return number;
    }

    static String string(Map<String, Object> data, String field) {
        if (!(data.get(field) instanceof String value) || value.isBlank()) throw new BadInput("Invalid " + field);
        return value;
    }

    static String optionalString(Map<String, Object> data, String field) {
        return data.containsKey(field) ? string(data, field) : null;
    }

    static Boolean optionalBoolean(Map<String, Object> data, String field) {
        if (!data.containsKey(field)) return null;
        if (!(data.get(field) instanceof Boolean value)) throw new BadInput("Invalid " + field);
        return value;
    }

    static Long optionalLong(Map<String, Object> data, String field) {
        if (!data.containsKey(field)) return null;
        if (!(data.get(field) instanceof Integer || data.get(field) instanceof Long))
            throw new BadInput("Invalid " + field);
        return ((Number) data.get(field)).longValue();
    }

    static Integer optionalInteger(Map<String, Object> data, String field) {
        if (!data.containsKey(field)) return null;
        if (!(data.get(field) instanceof Integer value)) throw new BadInput("Invalid " + field);
        return value;
    }

    static RuntimeException classify(IllegalArgumentException invalid) {
        String message = invalid.getMessage() == null ? "Invalid request" : invalid.getMessage();
        if (message.startsWith("Unknown run") || message.startsWith("Unknown run artifact")) return new Missing(message);
        if (message.startsWith("Run is not paused")) return new Conflict(message);
        if (message.contains("reused with different input")) return new Conflict(message);
        return new BadInput(message);
    }

    static final class BadInput extends RuntimeException { BadInput(String message) { super(message); } }
    static final class Missing extends RuntimeException { Missing(String message) { super(message); } }
    static final class Conflict extends RuntimeException { Conflict(String message) { super(message); } }
    record CommandResponse(String runId, String commandId, String deliveryStatus,
            tools.jackson.databind.JsonNode applicationOutcome) {}
}
