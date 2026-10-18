package com.brianmehrman.harness.runs;

import com.brianmehrman.harness.model.ProfileRevisionStore;
import com.brianmehrman.harness.execution.Limits;
import com.brianmehrman.harness.execution.PausePolicy;
import com.brianmehrman.harness.execution.RunSpec;
import com.brianmehrman.harness.execution.RunStatus;
import com.brianmehrman.harness.workspace.SnapshotHasher;
import com.brianmehrman.harness.workspace.SnapshotRef;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

/** Durable repository-task intake; the model never receives the live checkout. */
@Service
public class RepositoryWorkService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RepositorySourceImporter importer;
    private final ProfileRevisionStore profiles;
    private final BlobStore blobs;
    private final ReviewArtifactService reviews;
    private final JsonMapper json = JsonMapper.builder().build();

    public RepositoryWorkService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            RepositorySourceImporter importer, ProfileRevisionStore profiles, BlobStore blobs,
            ReviewArtifactService reviews) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transactions.setTimeout(20);
        this.importer = Objects.requireNonNull(importer);
        this.profiles = Objects.requireNonNull(profiles);
        this.blobs = Objects.requireNonNull(blobs);
        this.reviews = Objects.requireNonNull(reviews);
    }

    public WorkAccepted create(CreateWorkRequest request) {
        validate(request);
        String payload = json.writeValueAsString(request);
        String inputSha = SnapshotHasher.content("repository-work-v1\n" + payload);
        String runId = "run-" + SnapshotHasher.content("run-id-v1\n" + request.commandId());
        var existing = prior(request.commandId());
        if (existing != null) return same(request.commandId(), inputSha, existing);
        var profile = profiles.get(request.profileRevision());
        var source = importer.capture("main");
        return transactions.execute(tx -> {
            var winner = prior(request.commandId());
            if (winner != null) return same(request.commandId(), inputSha, winner);
            String instructionBlob = blobs.put("text/plain; charset=utf-8",
                    request.instructions().getBytes(StandardCharsets.UTF_8));
            String sourceJson = json.writeValueAsString(source.files());
            jdbc.update("insert into workspace_snapshot(sha256,files_json,byte_count,file_count) " +
                            "values (?,?::jsonb,?,?) on conflict do nothing",
                    source.snapshotSha256(), sourceJson, source.byteCount(), source.files().size());
            var spec = new RunSpec(runId, request.commandId(), inputSha, "repository-v1",
                    source.snapshotSha256(), "repository-review-v1", profile.id(), profile.fullDigest(),
                    source.snapshotSha256(), Limits.codingDefaults(), PausePolicy.defaults());
            jdbc.update("insert into run_request(run_id,command_id,input_sha256,spec_json,kind) " +
                            "values (?,?,?,?::jsonb,'REPOSITORY')",
                    runId, request.commandId(), inputSha, json.writeValueAsString(spec));
            jdbc.update("insert into run_workspace(run_id,benchmark_version,seed_sha256) values (?,'repository-v1',?)",
                    runId, source.snapshotSha256());
            jdbc.update("insert into run_snapshot(run_id,sha256) values (?,?)", runId, source.snapshotSha256());
            jdbc.update("insert into repository_work_item(run_id,title,instructions_blob_id,profile_revision," +
                            "base_commit,seed_sha256) values (?,?,?,?,?,?)",
                    runId, request.title(), instructionBlob, request.profileRevision(),
                    source.commitSha(), source.snapshotSha256());
            jdbc.update("insert into run_command(command_id,run_id,kind,payload_sha256,payload_json) " +
                            "values (?,?,'START',?,?::jsonb)", request.commandId(), runId, inputSha, payload);
            jdbc.update("insert into run_projection(run_id,version,status,snapshot_sha256) " +
                            "values (?,0,'QUEUED',?)", runId, source.snapshotSha256());
            return new WorkAccepted(runId, request.commandId(), "ACCEPTED");
        });
    }

    public Map<String, Object> list() {
        String select = "select w.run_id,w.title,w.base_commit,w.profile_revision," +
                "p.status,p.version,c.delivered_at is not null,w.created_at,p.updated_at,a.acquired_at " +
                "from repository_work_item w " +
                "join run_projection p on p.run_id=w.run_id join run_request r on r.run_id=w.run_id " +
                "join run_command c on c.command_id=r.command_id " +
                "left join run_admission a on a.run_id=w.run_id ";
        RowMapper<WorkSummary> mapper = (rs, index) -> new WorkSummary(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6),
                        rs.getBoolean(7) ? "DELIVERED" : "ACCEPTED", rs.getTimestamp(8).toInstant().toString(),
                        rs.getTimestamp(9).toInstant().toString(),
                        rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant().toString(), 0);
        List<WorkSummary> rows = new ArrayList<>(jdbc.query(select +
                "where p.status in ('QUEUED','PAUSED') or a.run_id is not null order by w.created_at,w.run_id", mapper));
        rows.addAll(jdbc.query(select + "where p.status not in ('QUEUED','PAUSED') and a.run_id is null " +
                "order by w.created_at desc,w.run_id desc limit 100", mapper));
        String activeId = jdbc.queryForList("select run_id from run_admission where slot_id=1", String.class)
                .stream().findFirst().orElse(null);
        WorkSummary active = null;
        List<WorkSummary> queue = new ArrayList<>();
        List<WorkSummary> paused = new ArrayList<>();
        List<WorkSummary> history = new ArrayList<>();
        for (WorkSummary row : rows) {
            if (row.workId().equals(activeId)) active = row;
            else if (row.status().equals("QUEUED")) queue.add(new WorkSummary(row.workId(), row.title(),
                    row.baseCommit(), row.profileRevision(), row.status(), row.version(),
                    row.deliveryStatus(), row.createdAt(), row.updatedAt(), row.startedAt(), queue.size() + 1));
            else if (row.status().equals("PAUSED")) paused.add(row);
            else history.add(row);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("active", active);
        BenchmarkActive benchmarkActive = null;
        if (active == null && activeId != null) {
            benchmarkActive = jdbc.query("select r.run_id,p.status,a.acquired_at,p.updated_at " +
                            "from run_request r join run_projection p on p.run_id=r.run_id " +
                            "join run_admission a on a.run_id=r.run_id " +
                            "where r.run_id=? and r.kind='BENCHMARK'",
                    (rs,n) -> new BenchmarkActive(rs.getString(1), rs.getString(2),
                            rs.getTimestamp(3).toInstant().toString(), rs.getTimestamp(4).toInstant().toString()),
                    activeId).stream().findFirst().orElse(null);
        }
        result.put("benchmarkActive", benchmarkActive);
        result.put("queue", queue);
        result.put("paused", paused);
        result.put("pausedRuns", jdbc.query("select r.run_id,p.status,p.updated_at from run_request r " +
                        "join run_projection p on p.run_id=r.run_id where r.kind='BENCHMARK' and p.status='PAUSED' " +
                        "order by p.updated_at",
                (rs, n) -> Map.of("runId", rs.getString(1), "status", rs.getString(2),
                        "updatedAt", rs.getTimestamp(3).toInstant().toString())));
        result.put("history", history);
        return result;
    }

    public Map<String, Object> detail(String runId) {
        var rows = jdbc.query("select w.title,w.instructions_blob_id,w.base_commit,w.seed_sha256," +
                        "w.profile_revision,p.status,p.version,p.stale,p.last_sequence,w.review_blob_id," +
                        "w.final_sha256,w.model_summary,c.delivered_at is not null,p.updated_at,p.snapshot_sha256 " +
                        "from repository_work_item w join run_projection p on p.run_id=w.run_id " +
                        "join run_request r on r.run_id=w.run_id join run_command c on c.command_id=r.command_id " +
                        "where w.run_id=?",
                (rs, index) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7),
                        rs.getBoolean(8), rs.getLong(9), rs.getString(10),rs.getString(11),rs.getString(12),
                        rs.getBoolean(13),rs.getTimestamp(14).toInstant(),rs.getString(15)}, runId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown work item");
        Object[] row = rows.getFirst();
        var result = new LinkedHashMap<String, Object>();
        result.put("workId", runId);
        result.put("title", row[0]);
        result.put("instructions", new String(blobs.get((String)row[1]), StandardCharsets.UTF_8));
        result.put("baseCommit", row[2]);
        result.put("seedSnapshotSha256", row[3]);
        result.put("profileRevision", row[4]);
        result.put("status", row[5]);
        result.put("version", row[6]);
        result.put("stale", (Boolean)row[7] || (Boolean)row[12] &&
                ((java.time.Instant)row[13]).isBefore(java.time.Instant.now().minusSeconds(15)) &&
                !RunStatus.isTerminal((String)row[5]));
        result.put("lastSequence", row[8]);
        String reviewBlobId = (String)row[9];
        String finalSnapshot = (String)row[10];
        if (reviewBlobId == null && RunStatus.isTerminal((String)row[5]) &&
                !"READY_FOR_REVIEW".equals(row[5]) && !"SUCCEEDED".equals(row[5])) {
            var unfinished = reviews.packageUnsubmitted(runId, new SnapshotRef((String)row[14]));
            reviewBlobId = unfinished.diffBlobId();
            finalSnapshot = unfinished.finalSha256();
        }
        result.put("reviewBlobId", reviewBlobId);
        String reviewed = finalSnapshot == null ? (String)row[14] : finalSnapshot;
        result.put("finalSnapshotSha256", reviewed);
        result.put("modelSummary", row[11]);
        // A receipt for an earlier snapshot says nothing about the code under review.
        result.put("checks", reviews.checks(runId).stream().map(check -> {
            var view = new LinkedHashMap<String, Object>();
            view.put("invocationId", check.invocationId());
            view.put("operation", check.operation());
            view.put("snapshotSha256", check.snapshotSha256());
            view.put("status", check.status());
            view.put("exitCode", check.exitCode());
            view.put("uncertain", check.uncertain());
            view.put("logBlobId", check.logBlobId());
            view.put("forFinalSnapshot", check.snapshotSha256().equals(reviewed));
            return view;
        }).toList());
        result.put("deliveryStatus", (Boolean)row[12] ? "DELIVERED" : "ACCEPTED");
        result.put("cancellations", jdbc.query("select command_id,delivered_at is not null,outcome_json::text " +
                        "from run_command where run_id=? and kind='CANCEL' order by accepted_at",
                (rs,n) -> Map.of("commandId",rs.getString(1),"deliveryStatus",rs.getBoolean(2) ? "DELIVERED" : "ACCEPTED",
                        "applicationOutcome",rs.getString(3) == null ? "PENDING" : rs.getString(3)), runId));
        result.putAll(PauseHistory.read(jdbc, blobs, runId, (String)row[5]));
        return result;
    }

    public List<WorkEvent> events(String runId, long after) {
        if (after < 0) throw new IllegalArgumentException("Invalid event cursor");
        var cursors = jdbc.queryForList("select p.last_sequence from run_projection p join repository_work_item w " +
                "on w.run_id=p.run_id where w.run_id=?", Long.class, runId);
        if (cursors.isEmpty()) throw new IllegalArgumentException("Unknown work item");
        return jdbc.query("select sequence,type,payload_blob_id from run_event " +
                        "where run_id=? and sequence>? and sequence<=? order by sequence limit 200",
                (rs, n) -> {
                    JsonNode payload = json.readTree(blobs.get(rs.getString(3)));
                    String type = rs.getString(2);
                    String status = payload.path("status").asText();
                    String stage = payload.path("stage").asText("terminal");
                    if ("STATE_CHANGED".equals(type)) stage = "QUEUED".equals(status) ? "queued" :
                            "RUNNING".equals(status) ? "model" : "READY_FOR_REVIEW".equals(status) ? "review" :
                            "PAUSED".equals(status) ? "paused" : "terminal";
                    if ("PAUSED".equals(type)) stage = "paused";
                    else if ("RESUMED".equals(type) || "NUDGED".equals(type) || "STOPPED".equals(type))
                        stage = payload.path("stage").asText("model");
                    String summary = payload.path("summary").asText("STATE_CHANGED".equals(type) ?
                            status.replace('_', ' ').toLowerCase() : type.replace('_', ' ').toLowerCase());
                    return new WorkEvent(rs.getLong(1), type, stage, summary,
                            payload.path("artifactDigest").isTextual() ? payload.path("artifactDigest").asText() : null);
                }, runId, after, cursors.getFirst());
    }

    public WorkAccepted cancel(String runId, String commandId, long expectedVersion) {
        if (commandId == null || commandId.isBlank()) throw new IllegalArgumentException("Command ID is required");
        String payload = json.writeValueAsString(Map.of("commandId", commandId, "expectedVersion", expectedVersion));
        String digest = SnapshotHasher.content("work-cancel-v1\n" + runId + "\n" + payload);
        return transactions.execute(tx -> {
            var previous = jdbc.query("select run_id,kind,payload_sha256 from run_command where command_id=?",
                    (rs, index) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, commandId);
            if (!previous.isEmpty()) {
                String[] prior = previous.getFirst();
                if (!runId.equals(prior[0]) || !"CANCEL".equals(prior[1]) || !digest.equals(prior[2]))
                    throw new IllegalArgumentException("Cancel command ID reused with different input");
                return new WorkAccepted(runId, commandId, "DELIVERED");
            }
            var rows = jdbc.query("select p.status,p.version,c.delivered_at is not null from repository_work_item w " +
                            "join run_projection p on p.run_id=w.run_id join run_request r on r.run_id=w.run_id " +
                            "join run_command c on c.command_id=r.command_id where w.run_id=?",
                    (rs, index) -> new Object[]{rs.getString(1), rs.getLong(2), rs.getBoolean(3)}, runId);
            if (rows.isEmpty()) throw new IllegalArgumentException("Unknown work item");
            Object[] row = rows.getFirst();
            if ((Long)row[1] != expectedVersion) throw new IllegalArgumentException("Expected version differs from current run version");
            if (!"QUEUED".equals(row[0]) || (Boolean)row[2])
                throw new IllegalArgumentException("Only undelivered queued work can be cancelled locally");
            // Lock START against the dispatcher. A held lease or any earlier attempt means the workflow may exist.
            Boolean mayBeDelivered = jdbc.queryForObject("select c.attempt_count>0 or c.lease_until is not null and c.lease_until>=now() " +
                    "from run_command c join run_request r on r.command_id=c.command_id where r.run_id=? for update of c",
                    Boolean.class, runId);
            if (Boolean.TRUE.equals(mayBeDelivered))
                throw new IllegalArgumentException("Expected START delivery to settle; retry the cancellation");
            jdbc.update("insert into run_command(command_id,run_id,kind,payload_sha256,payload_json," +
                            "delivered_at,outcome_json) values (?,?,'CANCEL',?,?::jsonb,now(),'{\"status\":\"APPLIED\"}'::jsonb)",
                    commandId, runId, digest, payload);
            jdbc.update("update repository_work_item set cancelled_at=now() where run_id=?", runId);
            jdbc.update("update run_projection set status='CANCELLED',version=version+1,updated_at=now() where run_id=?", runId);
            return new WorkAccepted(runId, commandId, "DELIVERED");
        });
    }

    private String[] prior(String commandId) {
        var rows = jdbc.query("select r.run_id,r.input_sha256,c.delivered_at is not null " +
                        "from run_request r join run_command c on c.command_id=r.command_id " +
                        "where r.command_id=?",
                (rs, index) -> new String[]{rs.getString(1), rs.getString(2),
                        rs.getBoolean(3) ? "DELIVERED" : "ACCEPTED"}, commandId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static WorkAccepted same(String commandId, String inputSha, String[] row) {
        if (!inputSha.equals(row[1])) throw new IllegalArgumentException("Start command ID reused with different input");
        return new WorkAccepted(row[0], commandId, row[2]);
    }

    private static void validate(CreateWorkRequest request) {
        if (request == null || request.commandId() == null || request.commandId().isBlank() ||
                request.commandId().length() > 200 || request.title() == null ||
                request.title().isBlank() || request.title().length() > 160 ||
                request.instructions() == null || request.instructions().isBlank() ||
                request.instructions().length() > 16_000 || request.profileRevision() == null ||
                request.profileRevision().isBlank()) throw new IllegalArgumentException("Invalid work request");
    }

    public record CreateWorkRequest(String commandId, String title, String instructions, String profileRevision) {}
    public record WorkAccepted(String workId, String commandId, String deliveryStatus) {}
    public record WorkSummary(String workId, String title, String baseCommit, String profileRevision,
            String status, long version, String deliveryStatus, String createdAt,
            String updatedAt, String startedAt, int position) {}
    public record WorkEvent(long sequence, String type, String stage, String summary, String artifactDigest) {}
    public record BenchmarkActive(String runId, String status, String startedAt, String updatedAt) {}
}
