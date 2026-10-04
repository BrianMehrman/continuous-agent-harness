package com.brianmehrman.harness.runs;

import com.brianmehrman.harness.execution.CancelCommand;
import com.brianmehrman.harness.execution.CommandResult;
import com.brianmehrman.harness.execution.RunSpec;

/** Task 7 supplies the Temporal implementation; Task 6 owns recoverable delivery. */
public interface WorkflowGateway {
    enum StartOutcome { STARTED, ALREADY_EXISTS }
    record StartReceipt(StartOutcome outcome, String inputSha256) {}
    record WorkflowDescription(String runId, String inputSha256, long version, String status,
            String snapshotSha256, String terminalResultBlobId) {}

    /** A stable run ID must never be reused for a different input, including after retention. */
    StartReceipt start(RunSpec spec);
    CommandResult cancel(String runId, CancelCommand command);
    WorkflowDescription describe(String runId);
}
