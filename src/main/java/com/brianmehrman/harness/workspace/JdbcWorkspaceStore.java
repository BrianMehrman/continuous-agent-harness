package com.brianmehrman.harness.workspace;

import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcWorkspaceStore implements WorkspaceStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final BenchmarkStarter starter;
    private final JsonMapper json = JsonMapper.builder().build();

    public JdbcWorkspaceStore(JdbcTemplate jdbc, PlatformTransactionManager manager, BenchmarkStarter starter) {
        this.jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(10);
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setTimeout(10);
        this.starter = starter;
    }

    @Override public SnapshotRef seed(String runId, String version) {
        identity(runId);
        if (!BenchmarkStarter.VERSION.equals(version)) throw new IllegalArgumentException("Unknown benchmark version");
        return transactions.execute(status -> {
            deadline();
            var existing = jdbc.queryForList("select seed_sha256 from run_workspace where run_id=?", String.class, runId);
            if (!existing.isEmpty()) return new SnapshotRef(existing.getFirst());
            var files = starter.files(version);
            var ref = persist(files);
            jdbc.update("insert into run_workspace(run_id,benchmark_version,seed_sha256) values (?,?,?) on conflict do nothing",
                    runId, version, ref.sha256());
            // A concurrent seed winner defines the run's immutable seed, even across deployments.
            String seed = jdbc.queryForObject("select seed_sha256 from run_workspace where run_id=?",String.class,runId);
            link(runId, seed);
            return new SnapshotRef(seed);
        });
    }

    @Override public SortedMap<String, String> files(String runId, SnapshotRef ref) {
        identity(runId);
        if (ref == null) throw new IllegalArgumentException("Snapshot is required");
        var rows = jdbc.queryForList("select s.files_json::text from workspace_snapshot s join run_snapshot r on r.sha256=s.sha256 where r.run_id=? and s.sha256=?",
                String.class, runId, ref.sha256());
        if (rows.isEmpty()) throw new IllegalArgumentException("Snapshot does not belong to this run");
        TreeMap<String,String> files = json.readValue(rows.getFirst(), new TypeReference<TreeMap<String,String>>() {});
        WorkspacePathPolicy.validateSnapshot(files);
        if (!SnapshotHasher.snapshot(files).equals(ref.sha256())) throw new IllegalStateException("Snapshot integrity check failed");
        return Collections.unmodifiableSortedMap(files);
    }

    @Override public SnapshotRef write(WriteRequest request) {
        validate(request);
        String input = SnapshotHasher.request(request);
        return transactions.execute(status -> {
            deadline();
            var receipt = receipt(request, input);
            if (receipt != null) return receipt;
            var files = new TreeMap<>(files(request.runId(), request.parent()));
            String previous = files.get(request.path());
            String actual = previous == null ? "absent" : SnapshotHasher.content(previous);
            if (!actual.equals(request.expectedSha256())) throw new IllegalArgumentException("Expected file hash does not match parent snapshot");
            files.put(request.path(), request.content());
            var result = persist(files);
            link(request.runId(), result.sha256());
            jdbc.update("insert into workspace_write_receipt(run_id,invocation_id,input_sha256,parent_sha256,result_sha256) values (?,?,?,?,?) on conflict do nothing",
                    request.runId(),request.invocationId(),input,request.parent().sha256(),result.sha256());
            // ON CONFLICT waits for the winner; mismatched reuse throws and rolls back speculative inserts.
            return receipt(request, input);
        });
    }

    private SnapshotRef receipt(WriteRequest request, String input) {
        var rows = jdbc.query("select input_sha256,result_sha256 from workspace_write_receipt where run_id=? and invocation_id=?",
                (rs,n) -> new String[]{rs.getString(1),rs.getString(2)},request.runId(),request.invocationId());
        if (rows.isEmpty()) return null;
        if (!input.equals(rows.getFirst()[0])) throw new IllegalArgumentException("Invocation ID reused with different input");
        return new SnapshotRef(rows.getFirst()[1]);
    }

    private SnapshotRef persist(SortedMap<String,String> files) {
        int bytes = WorkspacePathPolicy.validateSnapshot(files);
        var ref = new SnapshotRef(SnapshotHasher.snapshot(files));
        jdbc.update("insert into workspace_snapshot(sha256,files_json,byte_count,file_count) values (?,?::jsonb,?,?) on conflict do nothing",
                ref.sha256(), json.writeValueAsString(files), bytes, files.size());
        return ref;
    }
    private void link(String run, String digest) {
        jdbc.update("insert into run_snapshot(run_id,sha256) values (?,?) on conflict do nothing",run,digest);
    }
    private void deadline() {
        jdbc.execute("set local statement_timeout = '10s'");
        jdbc.execute("set local lock_timeout = '10s'");
    }
    private static void validate(WriteRequest request) {
        if (request == null || request.parent() == null) throw new IllegalArgumentException("Write and parent are required");
        identity(request.runId()); identity(request.invocationId());
        WorkspacePathPolicy.validateWritePath(request.path());
        if (request.expectedSha256() == null || !(request.expectedSha256().equals("absent") || request.expectedSha256().matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("Expected hash must be SHA-256 or absent");
        }
        WorkspacePathPolicy.validateContentLength(request.content());
        if (WorkspacePathPolicy.utf8(request.content()).length > WorkspacePathPolicy.MAX_FILE_BYTES) {
            throw new IllegalArgumentException("File exceeds 64 KiB");
        }
    }
    private static void identity(String value) {
        if (value == null || value.isBlank() || value.length() > 200 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid run or invocation identity");
        }
        WorkspacePathPolicy.utf8(value);
    }
}
