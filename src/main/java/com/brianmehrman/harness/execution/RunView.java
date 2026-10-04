package com.brianmehrman.harness.execution;

import java.time.Instant;

/** Rebuildable query projection; stale means semantic event history has a gap. */
public record RunView(String runId, long version, String status, String snapshotSha256,
        long lastSequence, boolean stale, Instant updatedAt, String terminalResultBlobId) {}
