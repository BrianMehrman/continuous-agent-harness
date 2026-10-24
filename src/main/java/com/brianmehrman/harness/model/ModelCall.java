package com.brianmehrman.harness.model;

import java.util.List;

public record ModelCall(String runId, String invocationId, int attempt,
        String profileRevision, String conversationBlobId, long deadlineEpochMillis, List<String> tools) {
    public ModelCall { if (tools != null) tools = List.copyOf(tools); }

    public ModelCall(String runId, String invocationId, int attempt,
            String profileRevision, String conversationBlobId, long deadlineEpochMillis) {
        this(runId, invocationId, attempt, profileRevision, conversationBlobId, deadlineEpochMillis, null);
    }
}
