package com.brianmehrman.harness.api;

import com.brianmehrman.harness.runs.RepositoryWorkService;
import com.brianmehrman.harness.runs.RepositorySourceImporter;
import com.brianmehrman.harness.runs.RunCommandService;
import com.brianmehrman.harness.runs.RunQueryService;
import com.brianmehrman.harness.execution.CancelCommand;
import com.brianmehrman.harness.execution.ResumeCommand;
import com.brianmehrman.harness.execution.StopCommand;
import com.brianmehrman.harness.execution.CommandResult;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@Profile("api")
public class WorkController {
    private static final Set<String> CREATE = Set.of("commandId", "title", "instructions");
    private static final Set<String> CREATE_OPTIONAL = Set.of("profileRevision", "role", "allowRemote",
            "costLimitMicros", "costBufferPercent");
    private static final Set<String> CANCEL = Set.of("commandId", "expectedVersion");
    private final RepositoryWorkService work;
    private final RepositorySourceImporter importer;
    private final RunCommandService commands;
    private final RunQueryService queries;

    public WorkController(RepositoryWorkService work, RepositorySourceImporter importer, RunCommandService commands,
            RunQueryService queries) {
        this.work = work;
        this.importer = importer;
        this.commands = commands;
        this.queries = queries;
    }

    @GetMapping("/api/work/base") public Object base() {
        try {
            var source = importer.capture("main");
            return Map.of("commitSha", source.commitSha(), "snapshotSha256", source.snapshotSha256());
        } catch (IllegalArgumentException invalid) { throw RunController.classify(invalid); }
        catch (IllegalStateException unavailable) { throw new ApiErrors.ServiceUnavailable("Local repository unavailable"); }
    }

    @PostMapping(value = "/api/work", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@RequestBody String body) {
        Map<String, Object> data = RunController.object(body, CREATE, CREATE_OPTIONAL);
        try {
            return ResponseEntity.accepted().body(work.create(new RepositoryWorkService.CreateWorkRequest(
                    RunController.string(data, "commandId"), RunController.string(data, "title"),
                    RunController.string(data, "instructions"), RunController.optionalString(data, "profileRevision"),
                    RunController.optionalString(data, "role"), RunController.optionalBoolean(data, "allowRemote"),
                    RunController.optionalLong(data, "costLimitMicros"),
                    RunController.optionalInteger(data, "costBufferPercent"))));
        } catch (IllegalArgumentException invalid) { throw RunController.classify(invalid); }
        catch (IllegalStateException unavailable) { throw new ApiErrors.ServiceUnavailable("Local repository unavailable"); }
    }

    @GetMapping("/api/work") public Object list() { return work.list(); }

    @GetMapping("/api/work/{workId}") public Object detail(@PathVariable String workId) {
        try { return work.detail(workId); }
        catch (IllegalArgumentException missing) { throw new RunController.Missing(missing.getMessage()); }
    }

    @GetMapping("/api/work/{workId}/events") public Object events(@PathVariable String workId,
            @RequestParam(defaultValue = "0") long after) {
        try { return work.events(workId, after); }
        catch (IllegalArgumentException invalid) {
            if (invalid.getMessage().startsWith("Unknown")) throw new RunController.Missing(invalid.getMessage());
            throw new RunController.BadInput(invalid.getMessage());
        }
    }

    @PostMapping(value = "/api/work/{workId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> cancel(@PathVariable String workId, @RequestBody String body) {
        Map<String, Object> data = RunController.object(body, CANCEL);
        if (!(data.get("expectedVersion") instanceof Number number)) throw new RunController.BadInput("Invalid expectedVersion");
        try {
            String commandId = RunController.string(data, "commandId");
            if ("DELIVERED".equals(work.detail(workId).get("deliveryStatus"))) {
                var accepted = commands.cancel(workId, new CancelCommand(commandId, number.longValue()));
                return ResponseEntity.accepted().body(new RepositoryWorkService.WorkAccepted(
                        workId, commandId, accepted.deliveryStatus()));
            }
            return ResponseEntity.accepted().body(work.cancel(workId, commandId, number.longValue()));
        }
        catch (IllegalArgumentException invalid) {
            if (invalid.getMessage().startsWith("Unknown")) throw new RunController.Missing(invalid.getMessage());
            if (invalid.getMessage().startsWith("Expected") || invalid.getMessage().contains("reused with different"))
                throw new RunController.Conflict(invalid.getMessage());
            throw new RunController.BadInput(invalid.getMessage());
        }
    }

    @PostMapping(value = "/api/work/{workId}/resume", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> resume(@PathVariable String workId, @RequestBody String body) {
        ResumeCommand command = RunController.resumeCommand(body);
        return pausedCommand(workId, command.commandId(), command.expectedVersion(), () -> commands.resume(workId, command));
    }

    @PostMapping(value = "/api/work/{workId}/stop", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> stop(@PathVariable String workId, @RequestBody String body) {
        StopCommand command = RunController.stopCommand(body);
        return pausedCommand(workId, command.commandId(), command.expectedVersion(), () -> commands.stop(workId, command));
    }

    private ResponseEntity<?> pausedCommand(String workId, String commandId, long expectedVersion,
            java.util.function.Supplier<CommandResult> accept) {
        try {
            work.detail(workId);
            if (!queries.commandExists(workId, commandId) && expectedVersion != queries.currentVersion(workId))
                throw new RunController.Conflict("Expected version differs from current run version");
            var accepted = accept.get();
            return ResponseEntity.accepted().body(new RepositoryWorkService.WorkAccepted(
                    workId, commandId, accepted.deliveryStatus()));
        }
        catch (IllegalArgumentException invalid) {
            if (invalid.getMessage().startsWith("Unknown")) throw new RunController.Missing(invalid.getMessage());
            if (invalid.getMessage().startsWith("Expected") || invalid.getMessage().startsWith("Run is not paused") ||
                    invalid.getMessage().contains("reused with different"))
                throw new RunController.Conflict(invalid.getMessage());
            throw new RunController.BadInput(invalid.getMessage());
        }
    }
}
