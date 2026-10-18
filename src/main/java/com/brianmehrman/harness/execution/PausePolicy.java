package com.brianmehrman.harness.execution;

/** Frozen stall thresholds, pause waiting limit, and extension ceilings. */
public record PausePolicy(int repeatedFailure, int repeatedContent, int noNewFacts, long waitMillis,
        int maxResumes, int resumeTurns, int resumeToolCalls, int resumeMinutes,
        int totalExtraTurns, int totalExtraToolCalls, int totalExtraMinutes) {
    public PausePolicy {
        if (repeatedFailure < 2 || repeatedContent < 2 || noNewFacts < 2 || waitMillis < 1 ||
                waitMillis > 7L * 24 * 3_600_000 || maxResumes < 0 || maxResumes > 3 ||
                resumeTurns < 0 || resumeTurns > 40 || resumeToolCalls < 0 || resumeToolCalls > 100 ||
                resumeMinutes < 0 || resumeMinutes > 30 || totalExtraTurns < 0 || totalExtraTurns > 120 ||
                totalExtraToolCalls < 0 || totalExtraToolCalls > 300 || totalExtraMinutes < 0 || totalExtraMinutes > 90)
            throw new IllegalArgumentException("Pause policy exceeds the local coding profile");
    }

    public static PausePolicy defaults() {
        return new PausePolicy(3, 3, 6, 7L * 24 * 3_600_000, 3, 40, 100, 30, 120, 300, 90);
    }

    public WorkingMemory.Thresholds thresholds() {
        return new WorkingMemory.Thresholds(repeatedFailure, repeatedContent, noNewFacts);
    }
}
