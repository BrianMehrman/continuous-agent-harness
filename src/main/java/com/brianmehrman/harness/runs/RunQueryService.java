package com.brianmehrman.harness.runs;

import com.brianmehrman.harness.execution.RunView;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read model for operator views. Only run-linked blobs are downloadable. */
@Service
@Profile("api")
public class RunQueryService {
    private final JdbcTemplate jdbc;
    private final RunProjectionRepository projections;
    private final WorkflowGateway gateway;
    private final BlobStore blobs;
    private final JsonMapper json = JsonMapper.builder().build();

    public RunQueryService(JdbcTemplate jdbc, RunProjectionRepository projections, WorkflowGateway gateway,
            BlobStore blobs) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.projections = Objects.requireNonNull(projections);
        this.gateway = Objects.requireNonNull(gateway);
        this.blobs = Objects.requireNonNull(blobs);
    }

    public List<RunView> list() {
        return jdbc.queryForList("select run_id from run_request order by created_at desc,run_id desc limit 100",
                String.class).stream().map(projections::view).toList();
    }

    public Map<String, Object> detail(String runId) {
        RunView view = projections.view(runId);
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from run_command where run_id=? " +
                "and kind='START' and delivered_at is not null)", Boolean.class, runId))) {
            try { view = projections.reconcile(runId, gateway); }
            catch (RuntimeException unavailable) {
                if (unavailable instanceof IllegalStateException) throw unavailable;
                // Temporal is unavailable: keep persisted history visible and mark its authority uncertain.
                view = new RunView(view.runId(), view.version(), view.status(), view.snapshotSha256(),
                        view.lastSequence(), true, view.updatedAt(), view.terminalResultBlobId());
            }
        }
        List<CommandView> commands = jdbc.query("select command_id,kind,delivered_at is not null," +
                        "outcome_json::text,last_error_code from run_command where run_id=? " +
                        "order by accepted_at,command_id",
                (rs, row) -> new CommandView(rs.getString(1), rs.getString(2),
                        rs.getBoolean(3) ? "DELIVERED" : "ACCEPTED",
                        rs.getString(4) == null ? null : json.readTree(rs.getString(4)), rs.getString(5)), runId);
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("projection", view);
        result.put("commands", commands);
        result.putAll(PauseHistory.read(jdbc, blobs, runId, view.status()));
        return result;
    }

    public List<EventView> events(String runId, long after) {
        projections.view(runId);
        if (after < 0) throw new IllegalArgumentException("Invalid event cursor");
        long contiguous = projections.view(runId).lastSequence();
        return jdbc.query("select sequence,event_id,type,payload_blob_id from run_event " +
                        "where run_id=? and sequence>? and sequence<=? order by sequence limit 200",
                (rs, row) -> new EventView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                runId, after, contiguous);
    }

    public Artifact artifact(String runId, String digest) {
        projections.view(runId);
        if (digest == null || !digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid artifact digest");
        var rows = jdbc.query("select b.media_type from artifact_blob b where b.sha256=? and (" +
                        "exists(select 1 from run_event e where e.run_id=? and e.payload_blob_id=b.sha256) or " +
                        "exists(select 1 from run_projection p where p.run_id=? and p.terminal_result_blob_id=b.sha256) or " +
                        "exists(select 1 from runner_invocation r where r.run_id=? and " +
                        "(r.reports_blob_id=b.sha256 or r.result_json->>'logBlobId'=b.sha256 or " +
                        "r.result_json->>'artifactBlobId'=b.sha256)) or " +
                        "exists(select 1 from evaluation e where e.run_id=? and e.artifact_sha256=b.sha256) or " +
                        "exists(select 1 from evaluation_runtime rt join evaluation e on e.invocation_id=rt.invocation_id " +
                        "where e.run_id=? and rt.artifact_sha256=b.sha256) or " +
                        "exists(select 1 from model_attempt m where m.run_id=? and " +
                        "(m.request_blob_id=b.sha256 or m.response_blob_id=b.sha256)) or " +
                        "exists(select 1 from repository_work_item w where w.run_id=? and w.review_blob_id=b.sha256))",
                (rs, row) -> rs.getString(1),
                digest, runId, runId, runId, runId, runId, runId, runId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown run artifact");
        return new Artifact(rows.getFirst(), blobs.get(digest));
    }

    public boolean commandExists(String runId, String commandId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from run_command " +
                "where run_id=? and command_id=?)", Boolean.class, runId, commandId));
    }

    public long currentVersion(String runId) {
        RunView view = projections.view(runId);
        boolean delivered = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from run_command " +
                "where run_id=? and kind='START' and delivered_at is not null)", Boolean.class, runId));
        if (delivered) {
            try { return gateway.authoritativeVersion(runId); }
            catch (RuntimeException unavailable) { throw new AuthorityUnavailable(); }
        }
        return view.version();
    }

    public CommandView command(String runId, String commandId) {
        return jdbc.query("select command_id,kind,delivered_at is not null,outcome_json::text,last_error_code " +
                        "from run_command where run_id=? and command_id=?",
                (rs, row) -> new CommandView(rs.getString(1), rs.getString(2),
                        rs.getBoolean(3) ? "DELIVERED" : "ACCEPTED",
                        rs.getString(4) == null ? null : json.readTree(rs.getString(4)), rs.getString(5)),
                runId, commandId).getFirst();
    }

    public record CommandView(String commandId, String kind, String deliveryStatus,
            JsonNode applicationOutcome, String lastErrorCode) {}
    public record EventView(long sequence, String eventId, String type, String payloadBlobId) {}
    public record Artifact(String mediaType, byte[] content) {}
    public static final class AuthorityUnavailable extends RuntimeException {
        public AuthorityUnavailable() { super("Authoritative workflow version unavailable"); }
    }
}
