package com.brianmehrman.harness.model;

public record ModelCall(String runId, String invocationId, int attempt,
        String profileRevision, String conversationBlobId, long deadlineEpochMillis) {}
