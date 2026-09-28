package com.brianmehrman.harness.runner;
public record InvocationResult(String invocationId, String inputSha256, String status,
    Integer exitCode, String logBlobId, boolean truncated, String artifactBlobId,
    int attempt, boolean uncertain) {}
