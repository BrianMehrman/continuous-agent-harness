package com.brianmehrman.harness.execution;

/** Immutable workflow input. Its identity is the accepted start payload digest. */
public record RunSpec(String runId, String commandId, String inputSha256, String benchmarkVersion,
        String definitionSha256, String evaluatorVersion, String profileRevision,
        String modelDigest, String seedSnapshotSha256, Limits limits) {}
