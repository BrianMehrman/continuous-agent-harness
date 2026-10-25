package com.brianmehrman.harness.runs;

import com.brianmehrman.harness.execution.CostTerms;
import com.brianmehrman.harness.execution.RunSpec;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Durable cost view reconstructed from frozen terms, attempt receipts, and applied resumes. */
public final class RunCost {
    private RunCost() {}

    public static Map<String, Object> read(JdbcTemplate jdbc, JsonMapper json, String runId) {
        String specJson = jdbc.queryForObject("select spec_json::text from run_request where run_id=?",
                String.class, runId);
        RunSpec spec = json.readValue(specJson, RunSpec.class);
        CostTerms terms = spec.terms() == null ? null : spec.terms().cost();
        var result = new LinkedHashMap<String, Object>();
        if (terms == null || terms.limitMicros() == null && terms.prices().free()) {
            result.put("cost", null);
            return result;
        }
        long[] charges = jdbc.queryForObject("select coalesce(sum(cost_micros),0)," +
                        "coalesce(sum(cost_micros) filter (where cost_estimated),0) " +
                        "from model_attempt where run_id=?",
                (rs, row) -> new long[]{rs.getBigDecimal(1).longValueExact(),
                        rs.getBigDecimal(2).longValueExact()}, runId);
        long extension = jdbc.queryForObject("select coalesce(sum(coalesce((payload_json->>'extraCostMicros')::bigint,0)),0) " +
                        "from run_command where run_id=? and kind='RESUME' " +
                        "and outcome_json->>'deliveryStatus'='APPLIED'",
                (rs, row) -> rs.getBigDecimal(1).longValueExact(), runId);
        Long limit = terms.limitMicros() == null ? null : Math.addExact(terms.limitMicros(), extension);
        var cost = new LinkedHashMap<String, Object>();
        cost.put("spentMicros", charges[0]);
        cost.put("estimatedMicros", charges[1]);
        cost.put("effectiveLimitMicros", limit == null ? null : terms.effectiveLimit(limit));
        cost.put("limitMicros", limit);
        cost.put("bufferPercent", terms.bufferPercent());
        result.put("cost", cost);
        return result;
    }
}
