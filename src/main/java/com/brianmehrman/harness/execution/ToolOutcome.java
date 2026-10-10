package com.brianmehrman.harness.execution;

import com.brianmehrman.harness.workspace.SnapshotRef;

/** Bounded tool result; the workflow alone advances its snapshot head. */
public record ToolOutcome(String invocationId, String providerCallId, String name,
        SnapshotRef snapshot, String responseJson, boolean attemptedSubmission, boolean passed,
        String failureCode) {
    public ToolOutcome(String invocationId, String providerCallId, String name,
            SnapshotRef snapshot, String responseJson, boolean attemptedSubmission, boolean passed) {
        this(invocationId, providerCallId, name, snapshot, responseJson, attemptedSubmission, passed, null);
    }
}
