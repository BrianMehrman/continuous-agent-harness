package com.brianmehrman.harness.runs;

import com.brianmehrman.harness.execution.CancelCommand;
import com.brianmehrman.harness.execution.CommandResult;
import com.brianmehrman.harness.execution.RunSpec;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Leased outbox delivery. No database transaction spans a gateway call. */
public class CommandDispatcher {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final WorkflowGateway gateway;
    private final JsonMapper json = JsonMapper.builder().build();

    public CommandDispatcher(JdbcTemplate jdbc, PlatformTransactionManager manager, WorkflowGateway gateway) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transactions.setTimeout(10);
        this.gateway = Objects.requireNonNull(gateway);
    }

    /** Returns false when no command can currently be claimed. */
    public boolean dispatchOnce() {
        return dispatchOnce(null);
    }

    /** The scoped form supports targeted recovery without claiming another run's outbox row. */
    public boolean dispatchOnce(String runId) {
        if (runId != null && runId.isBlank()) throw new IllegalArgumentException("Invalid run ID");
        Claim claim = transactions.execute(status -> claim(runId));
        if (claim == null) return false;
        try {
            String outcome = switch (claim.kind()) {
                case "START" -> deliverStart(claim);
                case "CANCEL" -> deliverCancel(claim);
                default -> throw new IllegalStateException("Unknown outbox command kind");
            };
            Integer acknowledged = transactions.execute(status -> jdbc.update(
                    "update run_command set delivered_at=now(),outcome_json=?::jsonb," +
                            "lease_owner=null,lease_until=null,last_error_code=null " +
                            "where command_id=? and lease_owner=? and delivered_at is null",
                    outcome, claim.commandId(), claim.owner()));
            if (acknowledged == null || acknowledged != 1)
                throw new IllegalStateException("Command delivery lease was lost before acknowledgment");
            return true;
        } catch (RuntimeException error) {
            transactions.executeWithoutResult(status -> jdbc.update(
                    "update run_command set lease_owner=null,lease_until=null," +
                            "next_attempt_at=now()+(? * interval '1 second'),last_error_code='DISPATCH_FAILED' " +
                            "where command_id=? and lease_owner=? and delivered_at is null",
                    backoffSeconds(claim.attempt()), claim.commandId(), claim.owner()));
            throw error;
        }
    }

    private Claim claim(String runId) {
        String scope = runId == null ? "" : "and c.run_id=? ";
        var ready = jdbc.query("select c.command_id,c.run_id,c.kind,c.payload_json::text,c.attempt_count " +
                        "from run_command c where c.delivered_at is null and c.next_attempt_at<=now() " +
                        "and (c.lease_until is null or c.lease_until<now()) " +
                        scope +
                        "and (c.kind='START' or exists (select 1 from run_command s where s.run_id=c.run_id " +
                        "and s.kind='START' and s.delivered_at is not null)) " +
                        "order by c.accepted_at,c.command_id for update skip locked limit 1",
                (rs, row) -> new Claim(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5) + 1, UUID.randomUUID().toString()),
                runId == null ? new Object[0] : new Object[]{runId});
        if (ready.isEmpty()) return null;
        Claim claim = ready.getFirst();
        jdbc.update("update run_command set lease_owner=?,lease_until=now()+interval '30 seconds'," +
                        "attempt_count=attempt_count+1 where command_id=?",
                claim.owner(), claim.commandId());
        return claim;
    }

    private String deliverStart(Claim claim) {
        String specJson = jdbc.queryForObject("select spec_json::text from run_request where run_id=?",
                String.class, claim.runId());
        RunSpec spec = json.readValue(specJson, RunSpec.class);
        WorkflowGateway.StartReceipt receipt = gateway.start(spec);
        if (receipt == null || !spec.inputSha256().equals(receipt.inputSha256()))
            throw new IllegalStateException("Workflow start identity mismatch");
        if (receipt.outcome() == WorkflowGateway.StartOutcome.ALREADY_EXISTS) {
            var described = gateway.describe(spec.runId());
            if (described == null || !spec.inputSha256().equals(described.inputSha256()))
                throw new IllegalStateException("Existing workflow input identity mismatch");
        }
        return json.writeValueAsString(receipt);
    }

    private String deliverCancel(Claim claim) {
        CancelCommand command = json.readValue(claim.payloadJson(), CancelCommand.class);
        CommandResult result = gateway.cancel(claim.runId(), command);
        if (result == null || !claim.runId().equals(result.runId()) ||
                !claim.commandId().equals(result.commandId()))
            throw new IllegalStateException("Workflow cancellation identity mismatch");
        return json.writeValueAsString(result);
    }

    private static int backoffSeconds(int attempt) {
        return Math.min(60, 1 << Math.min(attempt, 6));
    }

    private record Claim(String commandId, String runId, String kind, String payloadJson,
            int attempt, String owner) {}
}
