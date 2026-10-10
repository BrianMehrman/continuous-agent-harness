package com.brianmehrman.harness.execution;

import com.brianmehrman.harness.model.ToolRequest;
import com.brianmehrman.harness.workspace.SnapshotRef;
import java.util.Objects;

/** Host-owned identity and snapshot accompany an untrusted model tool request. */
public record ToolInvocation(String runId, String invocationId, SnapshotRef snapshot,
        ToolRequest request, long deadlineEpochMillis) {
    public ToolInvocation {
        if (runId == null || runId.isBlank() || invocationId == null || invocationId.isBlank())
            throw new IllegalArgumentException("Tool identity is required");
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(request);
        if (deadlineEpochMillis <= 0) throw new IllegalArgumentException("Tool deadline is required");
    }
}
