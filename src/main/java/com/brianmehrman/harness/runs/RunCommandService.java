package com.brianmehrman.harness.runs;

import com.brianmehrman.harness.benchmark.TaskTrackerDefinition;
import com.brianmehrman.harness.execution.CancelCommand;
import com.brianmehrman.harness.execution.CommandResult;
import com.brianmehrman.harness.execution.RunSpec;
import com.brianmehrman.harness.model.ProfileRevisionStore;
import com.brianmehrman.harness.workspace.SnapshotHasher;
import com.brianmehrman.harness.workspace.WorkspaceStore;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Accepts commands in PostgreSQL; gateway delivery is deliberately outside this transaction. */
@Service
public class RunCommandService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final WorkspaceStore workspaces;
    private final ProfileRevisionStore profiles;
    private final TaskTrackerDefinition definition;
    private final JsonMapper json = JsonMapper.builder().build();

    public RunCommandService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            WorkspaceStore workspaces, ProfileRevisionStore profiles, TaskTrackerDefinition definition) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transactions.setTimeout(15);
        this.workspaces = Objects.requireNonNull(workspaces);
        this.profiles = Objects.requireNonNull(profiles);
        this.definition = Objects.requireNonNull(definition);
    }

    public StartResult start(StartCommand command) {
        Objects.requireNonNull(command);
        String payload = json.writeValueAsString(command);
        String inputSha256 = SnapshotHasher.content("run-start-v1\n" + payload);
        String runId = "run-" + SnapshotHasher.content("run-id-v1\n" + command.commandId());
        return transactions.execute(status -> {
            var previous = jdbc.query("select run_id,input_sha256 from run_request where command_id=?",
                    (rs, row) -> new String[]{rs.getString(1), rs.getString(2)}, command.commandId());
            if (!previous.isEmpty()) return existingStart(command, inputSha256, previous.getFirst());
            if (!definition.benchmarkVersion().equals(command.benchmarkVersion()))
                throw new IllegalArgumentException("Unknown benchmark version");
            var profile = profiles.get(command.profileRevision());
            var seed = workspaces.seed(runId, command.benchmarkVersion());
            var spec = new RunSpec(runId, command.commandId(), inputSha256, command.benchmarkVersion(),
                    definition.starterDigest(), definition.evaluatorVersion(), profile.id(),
                    profile.fullDigest(), seed.sha256(), command.limits());
            int inserted = jdbc.update("insert into run_request(run_id,command_id,input_sha256,spec_json) " +
                            "values (?,?,?,?::jsonb) on conflict do nothing",
                    runId, command.commandId(), inputSha256, json.writeValueAsString(spec));
            if (inserted == 0) {
                var winner = jdbc.query("select run_id,input_sha256 from run_request where command_id=?",
                        (rs, row) -> new String[]{rs.getString(1), rs.getString(2)}, command.commandId());
                if (winner.isEmpty()) throw new IllegalStateException("Run identity collision");
                return existingStart(command, inputSha256, winner.getFirst());
            }
            jdbc.update("insert into run_command(command_id,run_id,kind,payload_sha256,payload_json) " +
                            "values (?,?, 'START', ?, ?::jsonb)",
                    command.commandId(), runId, inputSha256, payload);
            jdbc.update("insert into run_projection(run_id,version,status,snapshot_sha256) values (?,0,'QUEUED',?)",
                    runId, seed.sha256());
            return new StartResult(runId, command.commandId(), "ACCEPTED");
        });
    }

    public CommandResult cancel(String runId, CancelCommand command) {
        identity(runId);
        Objects.requireNonNull(command);
        String payload = json.writeValueAsString(command);
        String digest = SnapshotHasher.content("run-cancel-v1\n" + runId + "\n" + payload);
        return transactions.execute(status -> {
            var previous = jdbc.query("select run_id,kind,payload_sha256,delivered_at is not null " +
                            "from run_command where command_id=?",
                    (rs, row) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)},
                    command.commandId());
            if (!previous.isEmpty()) return existingCancel(runId, command, digest, previous.getFirst());
            if (jdbc.queryForObject("select count(*) from run_request where run_id=?", Integer.class, runId) == 0)
                throw new IllegalArgumentException("Unknown run");
            jdbc.update("insert into run_command(command_id,run_id,kind,payload_sha256,payload_json) " +
                            "values (?,?,'CANCEL',?,?::jsonb) on conflict do nothing",
                    command.commandId(), runId, digest, payload);
            var accepted = jdbc.query("select run_id,kind,payload_sha256,delivered_at is not null " +
                            "from run_command where command_id=?",
                    (rs, row) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)},
                    command.commandId());
            return existingCancel(runId, command, digest, accepted.getFirst());
        });
    }

    private StartResult existingStart(StartCommand command, String digest, String[] row) {
        if (!digest.equals(row[1])) throw new IllegalArgumentException("Start command ID reused with different input");
        Boolean delivered = jdbc.queryForObject("select delivered_at is not null from run_command " +
                "where command_id=? and run_id=? and kind='START'", Boolean.class, command.commandId(), row[0]);
        if (delivered == null) throw new IllegalStateException("Accepted start has no delivery record");
        return new StartResult(row[0], command.commandId(), delivered ? "DELIVERED" : "ACCEPTED");
    }

    private static CommandResult existingCancel(String runId, CancelCommand command, String digest, Object[] row) {
        if (!runId.equals(row[0]) || !"CANCEL".equals(row[1]) || !digest.equals(row[2]))
            throw new IllegalArgumentException("Cancel command ID reused with different input");
        return new CommandResult(runId, command.commandId(), (Boolean) row[3] ? "DELIVERED" : "ACCEPTED");
    }

    private static void identity(String value) {
        if (value == null || value.isBlank() || value.length() > 200 ||
                value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid run identity");
    }
}
