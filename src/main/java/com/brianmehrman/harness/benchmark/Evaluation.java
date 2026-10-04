package com.brianmehrman.harness.benchmark;
import java.util.List;
public record Evaluation(boolean passed, String snapshotSha256, String artifactSha256,
                         String evaluatorVersion, long seed, List<String> failedCases,
                         int reportedAgentTests, Outcome outcome) {
    public enum Outcome { PASSED, REJECTED, INFRASTRUCTURE_FAILED }
    public Evaluation {
        failedCases=List.copyOf(failedCases);
        if(passed != (outcome==Outcome.PASSED)) throw new IllegalArgumentException("Inconsistent evaluation outcome");
    }
}
