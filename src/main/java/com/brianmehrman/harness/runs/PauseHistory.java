package com.brianmehrman.harness.runs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Pause, resume, and nudge history read from durable run events. */
public final class PauseHistory {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private PauseHistory() {}

    public static Map<String, Object> read(JdbcTemplate jdbc, BlobStore blobs, String runId, String status) {
        Map<String, Object> pause = null;
        var resumes = new ArrayList<Map<String, Object>>();
        int nudges = 0;
        for (var row : jdbc.queryForList("select type,payload_blob_id from run_event where run_id=? " +
                "and type in ('PAUSED','RESUMED','NUDGED') order by sequence", runId)) {
            Map<String, Object> payload;
            try {
                payload = JSON.readValue(blobs.get((String) row.get("payload_blob_id")), new TypeReference<>() {});
            } catch (RuntimeException unreadable) {
                continue; // One unreadable event must not hide the rest of the detail view.
            }
            if (payload == null) continue;
            switch ((String) row.get("type")) {
                case "PAUSED" -> pause = payload;
                case "RESUMED" -> resumes.add(payload);
                default -> nudges++;
            }
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("pause", "PAUSED".equals(status) ? pause : null);
        result.put("resumes", resumes);
        result.put("nudges", nudges);
        result.put("label", resumes.stream().anyMatch(r -> r.get("hint") != null) ? "assisted"
                : resumes.isEmpty() ? null : "extended");
        return result;
    }
}
