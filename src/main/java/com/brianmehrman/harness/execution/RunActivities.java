package com.brianmehrman.harness.execution;

import com.brianmehrman.harness.model.ModelCall;
import com.brianmehrman.harness.model.ModelReply;
import java.util.List;
import io.temporal.activity.ActivityInterface;

/** I/O boundary for the deterministic coding workflow. */
@ActivityInterface
public interface RunActivities {
    boolean acquireAdmission(String runId);
    void releaseAdmission(String runId);
    String startConversation(RunSpec spec);
    ModelReply callModel(ModelCall call);
    ToolOutcome invokeTool(ToolInvocation call);
    String appendConversation(String previousBlobId, ModelReply reply, List<ToolOutcome> outcomes);
    void publish(String runId, long sequence, long version, String status,
            String snapshotSha256, String terminalResultBlobId);
    /** Request a stop and report whether the runner has persisted its result and finished cleanup. */
    boolean cancelRunner(String invocationId);
}
