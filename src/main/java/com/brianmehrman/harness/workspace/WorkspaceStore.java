package com.brianmehrman.harness.workspace;
import java.util.SortedMap;
public interface WorkspaceStore {
    SnapshotRef seed(String runId, String benchmarkVersion);
    SortedMap<String, String> files(String runId, SnapshotRef ref);
    SnapshotRef write(WriteRequest request);
}
