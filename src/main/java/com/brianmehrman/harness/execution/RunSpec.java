package com.brianmehrman.harness.execution;

/** Immutable workflow input. Its identity is the accepted start payload digest. */
public record RunSpec(String runId, String commandId, String inputSha256, String benchmarkVersion,
        String definitionSha256, String evaluatorVersion, String profileRevision,
        String modelDigest, String seedSnapshotSha256, Limits limits, PausePolicy pausePolicy) {
    public RunSpec(String runId, String commandId, String inputSha256, String benchmarkVersion,
            String definitionSha256, String evaluatorVersion, String profileRevision,
            String modelDigest, String seedSnapshotSha256, Limits limits) {
        this(runId, commandId, inputSha256, benchmarkVersion, definitionSha256, evaluatorVersion,
                profileRevision, modelDigest, seedSnapshotSha256, limits, null);
    }

    public PausePolicy policy() { return pausePolicy == null ? PausePolicy.defaults() : pausePolicy; }
}
