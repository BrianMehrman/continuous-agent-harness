package com.brianmehrman.harness.execution;

/** Frozen per-run bounds. Values above the local coding profile are not admitted. */
public record Limits(int modelTurns, int toolCalls, int modelAttempts, int perTurnModelAttempts,
        int runnerAttempts, long modelAttemptMillis, long buildTestMillis,
        long evaluationMillis, long fileOperationMillis, long runMillis) {
    public Limits {
        if (modelTurns < 1 || modelTurns > 40 || toolCalls < 1 || toolCalls > 100 ||
                modelAttempts < 1 || modelAttempts > 80 || perTurnModelAttempts < 1 ||
                perTurnModelAttempts > 2 || runnerAttempts < 1 || runnerAttempts > 2 ||
                modelAttemptMillis < 1 || modelAttemptMillis > 180_000 ||
                buildTestMillis < 1 || buildTestMillis > 120_000 ||
                evaluationMillis < 1 || evaluationMillis > 180_000 ||
                fileOperationMillis < 1 || fileOperationMillis > 10_000 ||
                runMillis < 1 || runMillis > 1_800_000)
            throw new IllegalArgumentException("Limits exceed the local coding profile");
    }

    public static Limits codingDefaults() {
        return new Limits(40, 100, 80, 2, 2, 180_000, 120_000, 180_000, 10_000, 1_800_000);
    }

    public Limits withModelTurns(int turns) {
        return new Limits(turns, toolCalls, modelAttempts, perTurnModelAttempts, runnerAttempts,
                modelAttemptMillis, buildTestMillis, evaluationMillis, fileOperationMillis, runMillis);
    }
}
