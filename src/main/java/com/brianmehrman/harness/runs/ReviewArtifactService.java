package com.brianmehrman.harness.runs;

import com.brianmehrman.harness.workspace.SnapshotRef;
import com.brianmehrman.harness.workspace.WorkspaceStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds review data from frozen host-owned snapshots and durable runner receipts. */
@Service
public class ReviewArtifactService {
    private static final int MAX_DIFF_BYTES = 8_388_608;
    private final JdbcTemplate jdbc;
    private final WorkspaceStore workspaces;
    private final BlobStore blobs;

    public ReviewArtifactService(JdbcTemplate jdbc, WorkspaceStore workspaces, BlobStore blobs) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.workspaces = Objects.requireNonNull(workspaces);
        this.blobs = Objects.requireNonNull(blobs);
    }

    @Transactional
    public ReviewBundle packageReview(String runId, SnapshotRef finalSnapshot, String summary) {
        if (summary == null || summary.isBlank() || summary.length() > 1000)
            throw new IllegalArgumentException("Invalid submission summary");
        var rows = jdbc.queryForList("select seed_sha256,base_commit,review_blob_id,final_sha256,model_summary from repository_work_item where run_id=? for update", runId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown repository work item");
        var row = rows.getFirst();
        if (row.get("review_blob_id") != null) {
            if (!finalSnapshot.sha256().equals(row.get("final_sha256")) || !summary.equals(row.get("model_summary")))
                throw new IllegalArgumentException("Submission snapshot differs from recorded result");
            return new ReviewBundle((String)row.get("base_commit"), (String)row.get("seed_sha256"),
                    finalSnapshot.sha256(), (String)row.get("review_blob_id"), checks(runId), (String)row.get("model_summary"));
        }
        var base = workspaces.files(runId, new SnapshotRef((String)row.get("seed_sha256")));
        var changed = workspaces.files(runId, finalSnapshot);
        String diff = diff(base, changed);
        String blob = blobs.put("text/x-diff; charset=utf-8", diff.getBytes(StandardCharsets.UTF_8));
        jdbc.update("update repository_work_item set review_blob_id=?,final_sha256=?,model_summary=? where run_id=?",
                blob, finalSnapshot.sha256(), summary, runId);
        return new ReviewBundle((String)row.get("base_commit"), (String)row.get("seed_sha256"),
                finalSnapshot.sha256(), blob, checks(runId), summary);
    }

    @Transactional
    public ReviewBundle packageUnsubmitted(String runId, SnapshotRef finalSnapshot) {
        var rows = jdbc.queryForList("select seed_sha256,base_commit,review_blob_id,final_sha256 " +
                "from repository_work_item where run_id=? for update", runId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown repository work item");
        var row = rows.getFirst();
        if (row.get("review_blob_id") != null)
            return new ReviewBundle((String)row.get("base_commit"), (String)row.get("seed_sha256"),
                    (String)row.get("final_sha256"), (String)row.get("review_blob_id"), checks(runId), null);
        var base = workspaces.files(runId, new SnapshotRef((String)row.get("seed_sha256")));
        var changed = workspaces.files(runId, finalSnapshot);
        String blob = blobs.put("text/x-diff; charset=utf-8", diff(base, changed).getBytes(StandardCharsets.UTF_8));
        jdbc.update("update repository_work_item set review_blob_id=?,final_sha256=? where run_id=?",
                blob, finalSnapshot.sha256(), runId);
        return new ReviewBundle((String)row.get("base_commit"), (String)row.get("seed_sha256"),
                finalSnapshot.sha256(), blob, checks(runId), null);
    }

    public List<CheckReceipt> checks(String runId) {
        return jdbc.query("select invocation_id,operation,snapshot_sha256,status,result_json->>'exitCode'," +
                        "result_json->>'uncertain',result_json->>'logBlobId' from runner_invocation where run_id=? order by invocation_id",
                (rs, n) -> new CheckReceipt(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5) == null ? null : Integer.valueOf(rs.getString(5)),
                        Boolean.parseBoolean(rs.getString(6)),rs.getString(7)), runId);
    }

    static String diff(java.util.Map<String, String> base, java.util.Map<String, String> changed) {
        var paths = new TreeSet<String>(); paths.addAll(base.keySet()); paths.addAll(changed.keySet());
        var out = new StringBuilder();
        for (String path : paths) {
            String before = base.get(path), after = changed.get(path);
            if (Objects.equals(before, after)) continue;
            List<Line> oldLines = lines(before), newLines = lines(after);
            int prefix = 0;
            while (prefix < oldLines.size() && prefix < newLines.size() &&
                    oldLines.get(prefix).equals(newLines.get(prefix))) prefix++;
            int suffix = 0;
            while (suffix < oldLines.size() - prefix && suffix < newLines.size() - prefix &&
                    oldLines.get(oldLines.size() - suffix - 1).equals(newLines.get(newLines.size() - suffix - 1))) suffix++;
            int start = Math.max(0, prefix - 3);
            int trailing = Math.min(3, suffix);
            int oldEnd = oldLines.size() - suffix + trailing;
            int newEnd = newLines.size() - suffix + trailing;
            out.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
            if (before == null) out.append("new file mode 100644\n");
            if (after == null) out.append("deleted file mode 100644\n");
            out.append("--- ").append(before == null ? "/dev/null" : "a/" + path).append('\n');
            out.append("+++ ").append(after == null ? "/dev/null" : "b/" + path).append('\n');
            out.append("@@ -").append(oldLines.isEmpty() ? 0 : start + 1).append(',').append(oldEnd - start)
                    .append(" +").append(newLines.isEmpty() ? 0 : start + 1).append(',').append(newEnd - start).append(" @@\n");
            for (int i = start; i < prefix; i++) appendLine(out, ' ', oldLines.get(i));
            for (int i = prefix; i < oldLines.size() - suffix; i++) appendLine(out, '-', oldLines.get(i));
            for (int i = prefix; i < newLines.size() - suffix; i++) appendLine(out, '+', newLines.get(i));
            for (int i = 0; i < trailing; i++)
                appendLine(out, ' ', oldLines.get(oldLines.size() - suffix + i));
            if (out.length() > MAX_DIFF_BYTES) throw new IllegalArgumentException("Review diff exceeds limit");
        }
        return out.toString();
    }

    private static void appendLine(StringBuilder out, char prefix, Line line) {
        out.append(prefix).append(line.text()).append('\n');
        if (!line.terminated()) out.append("\\ No newline at end of file\n");
    }

    private static List<Line> lines(String content) {
        if (content == null || content.isEmpty()) return List.of();
        String[] split = content.split("\n", -1);
        var lines = new ArrayList<Line>();
        for (int i = 0; i < split.length - 1; i++) lines.add(new Line(split[i], true));
        if (!content.endsWith("\n")) lines.add(new Line(split[split.length - 1], false));
        return lines;
    }

    private record Line(String text, boolean terminated) {}

    public record CheckReceipt(String invocationId, String operation, String snapshotSha256,
            String status, Integer exitCode, boolean uncertain, String logBlobId) {}
    public record ReviewBundle(String baseCommit, String seedSha256, String finalSha256,
            String diffBlobId, List<CheckReceipt> checks, String modelSummary) {}
}
