package com.brianmehrman.harness.workspace;
public record SnapshotRef(String sha256) {
    public SnapshotRef {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid snapshot digest");
        }
    }
}
