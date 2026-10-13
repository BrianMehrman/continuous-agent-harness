package com.brianmehrman.harness.api;

import com.brianmehrman.harness.runs.RepositoryWorkService;
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

@RestController
@Profile("api")
public class WorkController {
    private static final Set<String> CREATE = Set.of("commandId", "title", "instructions", "profileRevision");
    private static final Set<String> CANCEL = Set.of("commandId", "expectedVersion");
    private final RepositoryWorkService work;

    public WorkController(RepositoryWorkService work) { this.work = work; }

    @PostMapping(value = "/api/work", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@RequestBody String body) {
        Map<String, Object> data = RunController.object(body, CREATE);
        try {
            return ResponseEntity.accepted().body(work.create(new RepositoryWorkService.CreateWorkRequest(
                    RunController.string(data, "commandId"), RunController.string(data, "title"),
                    RunController.string(data, "instructions"), RunController.string(data, "profileRevision"))));
        } catch (IllegalArgumentException invalid) { throw RunController.classify(invalid); }
        catch (IllegalStateException unavailable) { throw new ApiErrors.ServiceUnavailable("Local repository unavailable"); }
    }

    @GetMapping("/api/work") public Object list() { return work.list(); }

    @GetMapping("/api/work/{workId}") public Object detail(@PathVariable String workId) {
        try { return work.detail(workId); }
        catch (IllegalArgumentException missing) { throw new RunController.Missing(missing.getMessage()); }
    }

    @PostMapping(value = "/api/work/{workId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> cancel(@PathVariable String workId, @RequestBody String body) {
        Map<String, Object> data = RunController.object(body, CANCEL);
        if (!(data.get("expectedVersion") instanceof Number number)) throw new RunController.BadInput("Invalid expectedVersion");
        try { return ResponseEntity.accepted().body(work.cancel(workId,
                RunController.string(data, "commandId"), number.longValue())); }
        catch (IllegalArgumentException invalid) {
            if (invalid.getMessage().startsWith("Unknown")) throw new RunController.Missing(invalid.getMessage());
            if (invalid.getMessage().startsWith("Expected") || invalid.getMessage().contains("reused with different"))
                throw new RunController.Conflict(invalid.getMessage());
            throw new RunController.BadInput(invalid.getMessage());
        }
    }
}
