package com.brianmehrman.harness.runner;
import com.brianmehrman.harness.workspace.SnapshotRef;
public record InvocationRequest(String runId, String invocationId, SnapshotRef snapshot,
                                Operation operation, long deadlineEpochMillis) {}
