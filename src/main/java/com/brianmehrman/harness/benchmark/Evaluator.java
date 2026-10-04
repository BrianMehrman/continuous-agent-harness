package com.brianmehrman.harness.benchmark;
import com.brianmehrman.harness.workspace.SnapshotRef;
public interface Evaluator {
    Evaluation evaluate(String runId, String invocationId, SnapshotRef snapshot, long seed, long deadlineEpochMillis);
}
