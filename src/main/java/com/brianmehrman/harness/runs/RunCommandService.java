package com.brianmehrman.harness.runs;

import com.brianmehrman.harness.benchmark.TaskTrackerDefinition;
import com.brianmehrman.harness.execution.CancelCommand;
import com.brianmehrman.harness.execution.CommandResult;
import com.brianmehrman.harness.execution.PausePolicy;
import com.brianmehrman.harness.execution.ResumeCommand;
import com.brianmehrman.harness.execution.RunSpec;
import com.brianmehrman.harness.execution.StopCommand;
import com.brianmehrman.harness.model.OllamaProfile;
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
    private final RunTermsResolver resolver;
    private final TaskTrackerDefinition definition;
    private final BlobStore blobs;
    private final JsonMapper json = JsonMapper.builder().build();

    public RunCommandService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            WorkspaceStore workspaces, RunTermsResolver resolver, TaskTrackerDefinition definition,
            BlobStore blobs) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transactions.setTimeout(15);
        this.workspaces = Objects.requireNonNull(workspaces);
        this.resolver = Objects.requireNonNull(resolver);
        this.definition = Objects.requireNonNull(definition);
        this.blobs = Objects.requireNonNull(blobs);
    }

    public StartResult start(StartCommand command) {
        Objects.requireNonNull(command);
        String payload = json.writeValueAsString(command);
        String inputSha256 = SnapshotHasher.content("run-start-v1\n" + payload);
        String runId = "run-" + SnapshotHasher.content("run-id-v1\n" + command.commandId());
        var prior = jdbc.query("select run_id,input_sha256 from run_request where command_id=?",
                (rs, row) -> new String[]{rs.getString(1), rs.getString(2)}, command.commandId());
        if (!prior.isEmpty()) return existingStart(command, inputSha256, prior.getFirst());
        var resolved = resolver.resolve(new RunOptions(command.role(), command.profileRevision(),
                Boolean.TRUE.equals(command.allowRemote()), command.costLimitMicros(), command.costBufferPercent()));
        return transactions.execute(status -> {
            var previous = jdbc.query("select run_id,input_sha256 from run_request where command_id=?",
                    (rs, row) -> new String[]{rs.getString(1), rs.getString(2)}, command.commandId());
            if (!previous.isEmpty()) return existingStart(command, inputSha256, previous.getFirst());
            if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from run_command where command_id=?)",
                    Boolean.class, command.commandId())))
                throw new IllegalArgumentException("Start command ID reused with different input");
            if (!definition.benchmarkVersion().equals(command.benchmarkVersion()))
                throw new IllegalArgumentException("Unknown benchmark version");
            var profile = resolved.profile();
            var seed = workspaces.seed(runId, command.benchmarkVersion());
            var spec = new RunSpec(runId, command.commandId(), inputSha256, command.benchmarkVersion(),
                    definition.starterDigest(), definition.evaluatorVersion(), profile.id(),
                    profile instanceof OllamaProfile ollama
                            ? ollama.fullDigest() : profile.modelName(),
                    seed.sha256(), command.limits(), PausePolicy.defaults(), resolved.terms());
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
        Objects.requireNonNull(command);
        return control(runId, "CANCEL", command.commandId(), command);
    }

    public CommandResult resume(String runId, ResumeCommand command) {
        Objects.requireNonNull(command);
        if (!exists(command.commandId())) {
            requirePaused(runId);
            RunSpec spec = json.readValue(jdbc.queryForObject("select spec_json::text from run_request where run_id=?",
                    String.class, runId), RunSpec.class);
            PausePolicy policy = spec.policy();
            if (command.extraTurns() > policy.resumeTurns() || command.extraToolCalls() > policy.resumeToolCalls() ||
                    command.extraMinutes() > policy.resumeMinutes())
                throw new IllegalArgumentException("Resume exceeds the per-resume ceiling");
            String reached = pauseReason(runId);
            if ("TURN_LIMIT".equals(reached) && command.extraTurns() <= 0 ||
                    "TOOL_LIMIT".equals(reached) && command.extraToolCalls() <= 0 ||
                    "TIME_LIMIT".equals(reached) && command.extraMinutes() <= 0)
                throw new IllegalArgumentException("Resume must extend the limit that was reached: " + reached);
            validateCostExtension(runId, spec, command, reached);
            var prior = jdbc.query("select payload_json::text from run_command where run_id=? and kind='RESUME' " +
                            "and (delivered_at is null or outcome_json->>'deliveryStatus'='APPLIED')",
                    (rs, row) -> json.readValue(rs.getString(1), ResumeCommand.class), runId);
            if (prior.size() + 1 > policy.maxResumes() ||
                    prior.stream().mapToInt(ResumeCommand::extraTurns).sum() + command.extraTurns() > policy.totalExtraTurns() ||
                    prior.stream().mapToInt(ResumeCommand::extraToolCalls).sum() + command.extraToolCalls() > policy.totalExtraToolCalls() ||
                    prior.stream().mapToInt(ResumeCommand::extraMinutes).sum() + command.extraMinutes() > policy.totalExtraMinutes())
                throw new IllegalArgumentException("Resume exceeds the run's extension ceiling");
        }
        return control(runId, "RESUME", command.commandId(), command);
    }

    private void validateCostExtension(String runId, RunSpec spec, ResumeCommand command, String reason) {
        String message = "Resume must raise the cost limit above the next call's worst case";
        if ("COST_LIMIT".equals(reason) && command.extraCostMicros() <= 0)
            throw new IllegalArgumentException(message);
        if (command.extraCostMicros() == 0) return;
        if (spec.terms() == null || spec.terms().cost() == null) {
            if ("COST_LIMIT".equals(reason)) throw new IllegalArgumentException(message);
            return;
        }
        @SuppressWarnings("unchecked") var current = (java.util.Map<String, Object>) RunCost.read(jdbc, json, runId).get("cost");
        if (current == null || current.get("limitMicros") == null) {
            if ("COST_LIMIT".equals(reason)) throw new IllegalArgumentException(message);
            return;
        }
        long limit;
        try { limit = Math.addExact((Long) current.get("limitMicros"), command.extraCostMicros()); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException(message, overflow); }
        if ("COST_LIMIT".equals(reason)) {
            long spent = (Long) current.get("spentMicros");
            long effective = spec.terms().cost().effectiveLimit(limit);
            if (spent > effective || spec.terms().cost().worstCase().micros() > effective - spent)
                throw new IllegalArgumentException(message);
        }
    }

    public CommandResult stop(String runId, StopCommand command) {
        Objects.requireNonNull(command);
        if (!exists(command.commandId())) requirePaused(runId);
        return control(runId, "STOP", command.commandId(), command);
    }

    private boolean exists(String commandId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from run_command where command_id=?)",
                Boolean.class, commandId));
    }

    private String pauseReason(String runId) {
        var ids = jdbc.queryForList("select payload_blob_id from run_event where run_id=? and type='PAUSED' " +
                "order by sequence desc limit 1", String.class, runId);
        if (ids.isEmpty()) return null;
        try {
            var payload = json.readTree(blobs.get(ids.getFirst()));
            return payload.path("reason").asText(null);
        } catch (RuntimeException unreadable) {
            return null; // The workflow applies the same rule when it receives the command.
        }
    }

    private void requirePaused(String runId) {
        var status = jdbc.queryForList("select status from run_projection where run_id=?", String.class, runId);
        if (status.isEmpty()) throw new IllegalArgumentException("Unknown run");
        if (!"PAUSED".equals(status.getFirst())) throw new IllegalArgumentException("Run is not paused");
    }

    private CommandResult control(String runId, String kind, String commandId, Object command) {
        identity(runId);
        String payload = json.writeValueAsString(command);
        String digest = SnapshotHasher.content("run-" + kind.toLowerCase() + "-v1\n" + runId + "\n" + payload);
        return transactions.execute(status -> {
            var previous = jdbc.query("select run_id,kind,payload_sha256,delivered_at is not null " +
                            "from run_command where command_id=?",
                    (rs, row) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)},
                    commandId);
            if (!previous.isEmpty()) return existingControl(runId, kind, commandId, digest, previous.getFirst());
            if (jdbc.queryForObject("select count(*) from run_request where run_id=?", Integer.class, runId) == 0)
                throw new IllegalArgumentException("Unknown run");
            jdbc.update("insert into run_command(command_id,run_id,kind,payload_sha256,payload_json) " +
                            "values (?,?,?,?,?::jsonb) on conflict do nothing",
                    commandId, runId, kind, digest, payload);
            var accepted = jdbc.query("select run_id,kind,payload_sha256,delivered_at is not null " +
                            "from run_command where command_id=?",
                    (rs, row) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)},
                    commandId);
            return existingControl(runId, kind, commandId, digest, accepted.getFirst());
        });
    }

    private StartResult existingStart(StartCommand command, String digest, String[] row) {
        if (!digest.equals(row[1])) throw new IllegalArgumentException("Start command ID reused with different input");
        Boolean delivered = jdbc.queryForObject("select delivered_at is not null from run_command " +
                "where command_id=? and run_id=? and kind='START'", Boolean.class, command.commandId(), row[0]);
        if (delivered == null) throw new IllegalStateException("Accepted start has no delivery record");
        return new StartResult(row[0], command.commandId(), delivered ? "DELIVERED" : "ACCEPTED");
    }

    private static CommandResult existingControl(String runId, String kind, String commandId, String digest, Object[] row) {
        if (!runId.equals(row[0]) || !kind.equals(row[1]) || !digest.equals(row[2]))
            throw new IllegalArgumentException(kind.charAt(0) + kind.substring(1).toLowerCase() +
                    " command ID reused with different input");
        return new CommandResult(runId, commandId, (Boolean) row[3] ? "DELIVERED" : "ACCEPTED");
    }

    private static void identity(String value) {
        if (value == null || value.isBlank() || value.length() > 200 ||
                value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid run identity");
    }
}
