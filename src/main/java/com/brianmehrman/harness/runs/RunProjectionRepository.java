package com.brianmehrman.harness.runs;

import com.brianmehrman.harness.execution.RunView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Idempotent semantic event ledger and contiguous, rebuildable query projection. */
@Repository
public class RunProjectionRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final BlobStore blobs;
    private final JsonMapper json = JsonMapper.builder().build();

    public RunProjectionRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, BlobStore blobs) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transactions.setTimeout(10);
        this.blobs = Objects.requireNonNull(blobs);
    }

    public RunView append(RunEvent event) {
        Objects.requireNonNull(event);
        return transactions.execute(status -> {
            var sameId = jdbc.query("select run_id,sequence,type,payload_blob_id from run_event where event_id=?",
                    (rs, row) -> new RunEvent(rs.getString(1), rs.getLong(2), event.eventId(),
                            rs.getString(3), rs.getString(4)), event.eventId());
            if (!sameId.isEmpty()) {
                if (!event.equals(sameId.getFirst())) throw new IllegalStateException("Run event ID reused with different payload");
                return view(event.runId());
            }
            jdbc.update("insert into run_event(run_id,sequence,event_id,type,payload_blob_id) " +
                            "values (?,?,?,?,?) on conflict do nothing",
                    event.runId(), event.sequence(), event.eventId(), event.type(), event.payloadBlobId());
            var atSequence = jdbc.query("select event_id,type,payload_blob_id from run_event " +
                            "where run_id=? and sequence=?",
                    (rs, row) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)},
                    event.runId(), event.sequence());
            if (atSequence.isEmpty() || !event.eventId().equals(atSequence.getFirst()[0]) ||
                    !event.type().equals(atSequence.getFirst()[1]) ||
                    !event.payloadBlobId().equals(atSequence.getFirst()[2]))
                throw new IllegalStateException("Run event sequence reused with different payload");
            RunView current = lockedView(event.runId());
            long sequence = current.lastSequence();
            long version = current.version();
            String state = current.status();
            String snapshot = current.snapshotSha256();
            String result = current.terminalResultBlobId();
            long lastEventVersion = -1;
            while (true) {
                var next = jdbc.queryForList("select payload_blob_id from run_event where run_id=? and sequence=?",
                        String.class, event.runId(), sequence + 1);
                if (next.isEmpty()) break;
                EventState payload = parse(next.getFirst());
                lastEventVersion = Math.max(lastEventVersion, payload.version());
                if (payload.version() >= version) {
                    if (payload.snapshotSha256() != null) requireSnapshot(event.runId(), payload.snapshotSha256());
                    version = payload.version();
                    state = payload.status();
                    snapshot = payload.snapshotSha256() == null ? snapshot : payload.snapshotSha256();
                    result = payload.terminalResultBlobId();
                }
                sequence++;
            }
            boolean gap = jdbc.queryForObject("select exists(select 1 from run_event where run_id=? and sequence>?)",
                    Boolean.class, event.runId(), sequence);
            boolean stale = gap || current.stale() && lastEventVersion < current.version();
            jdbc.update("update run_projection set version=?,status=?,snapshot_sha256=?,last_sequence=?," +
                            "stale=?,updated_at=now(),terminal_result_blob_id=? where run_id=?",
                    version, state, snapshot, sequence, stale, result, event.runId());
            return view(event.runId());
        });
    }

    public RunView view(String runId) {
        var rows = jdbc.query("select run_id,version,status,snapshot_sha256,last_sequence,stale,updated_at," +
                        "terminal_result_blob_id from run_projection where run_id=?",
                (rs, row) -> map(rs), runId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown run");
        return rows.getFirst();
    }

    /** Rechecks authoritative identity and state while keeping missing event history visible. */
    public RunView reconcile(String runId, WorkflowGateway gateway) {
        Objects.requireNonNull(gateway);
        WorkflowGateway.WorkflowDescription described = gateway.describe(runId);
        if (described == null) return view(runId);
        return transactions.execute(status -> {
            String input = jdbc.queryForObject("select input_sha256 from run_request where run_id=?",
                    String.class, runId);
            if (!runId.equals(described.runId()) || !input.equals(described.inputSha256()))
                throw new IllegalStateException("Workflow description identity mismatch");
            RunView current = lockedView(runId);
            if (described.version() > current.version()) {
                if (described.snapshotSha256() != null) requireSnapshot(runId, described.snapshotSha256());
                jdbc.update("update run_projection set version=?,status=?,snapshot_sha256=?," +
                                "terminal_result_blob_id=?,stale=true,updated_at=now() where run_id=?",
                        described.version(), described.status(), described.snapshotSha256(),
                        described.terminalResultBlobId(), runId);
            }
            return view(runId);
        });
    }

    private RunView lockedView(String runId) {
        var rows = jdbc.query("select run_id,version,status,snapshot_sha256,last_sequence,stale,updated_at," +
                        "terminal_result_blob_id from run_projection where run_id=? for update",
                (rs, row) -> map(rs), runId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown run");
        return rows.getFirst();
    }

    private static RunView map(ResultSet rs) throws SQLException {
        return new RunView(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getLong(5), rs.getBoolean(6), rs.getTimestamp(7).toInstant(), rs.getString(8));
    }

    private EventState parse(String blobId) {
        JsonNode root = json.readTree(blobs.get(blobId));
        if (root == null || !root.isObject() || !root.has("version") || !root.path("version").isIntegralNumber() ||
                root.path("version").asLong() < 0 || !root.path("status").isTextual() ||
                !root.path("status").asText().matches("[A-Z][A-Z_]{0,63}"))
            throw new IllegalArgumentException("Invalid semantic event payload");
        String snapshot = root.path("snapshotSha256").isMissingNode() || root.path("snapshotSha256").isNull()
                ? null : root.path("snapshotSha256").asText();
        String result = root.path("terminalResultBlobId").isMissingNode() || root.path("terminalResultBlobId").isNull()
                ? null : root.path("terminalResultBlobId").asText();
        if (snapshot != null && !snapshot.matches("[0-9a-f]{64}") ||
                result != null && !result.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid semantic event reference");
        return new EventState(root.path("version").asLong(), root.path("status").asText(), snapshot, result);
    }

    private void requireSnapshot(String runId, String digest) {
        if (jdbc.queryForObject("select count(*) from run_snapshot where run_id=? and sha256=?",
                Integer.class, runId, digest) == 0)
            throw new IllegalStateException("Event snapshot does not belong to run");
    }

    private record EventState(long version, String status, String snapshotSha256, String terminalResultBlobId) {}
}
