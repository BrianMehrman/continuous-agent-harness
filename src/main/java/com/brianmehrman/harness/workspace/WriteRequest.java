package com.brianmehrman.harness.workspace;
public record WriteRequest(String runId, String invocationId, SnapshotRef parent,
                           String path, String expectedSha256, String content) {}
