package com.brianmehrman.harness.execution;

import com.brianmehrman.harness.runs.WorkflowGateway;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** One durable benchmark slot shared by all worker processes. */
@Repository
public class RunAdmissionStore {
    private final JdbcTemplate jdbc;

    public RunAdmissionStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public boolean acquire(String runId) {
        identity(runId);
        jdbc.update("insert into run_admission(slot_id,run_id) values (1,?) on conflict do nothing", runId);
        return runId.equals(jdbc.queryForObject("select run_id from run_admission where slot_id=1",
                String.class));
    }

    /** Only the authoritative workflow's terminal transition may call this method. */
    public boolean releaseAfterTerminalTransition(String runId) {
        identity(runId);
        return jdbc.update("delete from run_admission where slot_id=1 and run_id=?", runId) == 1;
    }

    public Optional<String> reservedRunId() {
        var rows = jdbc.queryForList("select run_id from run_admission where slot_id=1", String.class);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** Recover a slot only after Temporal confirms that its workflow completed. */
    public boolean reconcileCompleted(WorkflowGateway gateway) {
        var reserved = reservedRunId();
        if (reserved.isEmpty()) return false;
        var description = gateway.describe(reserved.get());
        if (description == null || !switch (description.status()) {
            case "SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED", "LIMIT_EXHAUSTED",
                    "INFRASTRUCTURE_FAILED", "CONTEXT_OVERFLOW",
                    "WORKFLOW_EXECUTION_STATUS_COMPLETED" -> true;
            default -> false;
        }) return false;
        return releaseAfterTerminalTransition(reserved.get());
    }

    private static void identity(String runId) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("Run ID is required");
    }
}
