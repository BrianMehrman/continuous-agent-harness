package com.brianmehrman.harness.runs;

import com.brianmehrman.harness.execution.RunSpec;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Operator-facing role and model from the frozen run input. */
final class RunIdentity {
    private RunIdentity() {}

    static Map<String, Object> read(JdbcTemplate jdbc, JsonMapper json, String runId) {
        String stored = jdbc.queryForObject("select spec_json::text from run_request where run_id=?",
                String.class, runId);
        RunSpec spec = json.readValue(stored, RunSpec.class);
        var result = new LinkedHashMap<String, Object>();
        result.put("role", spec.terms() == null ? null : spec.terms().roleName());
        result.put("model", spec.terms() == null ? null : spec.terms().modelName());
        return result;
    }
}
