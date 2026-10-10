package com.brianmehrman.harness.runs;

import com.brianmehrman.harness.execution.CancelCommand;
import com.brianmehrman.harness.execution.CodingWorkflow;
import com.brianmehrman.harness.execution.CommandResult;
import com.brianmehrman.harness.execution.RunSpec;
import com.brianmehrman.harness.execution.RunView;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Stable-ID Temporal delivery, with frozen input identity retained in workflow memo. */
@Component
@Profile("api | worker")
public class TemporalWorkflowGateway implements WorkflowGateway {
    private final WorkflowClient client;
    private final String taskQueue;

    public TemporalWorkflowGateway(WorkflowClient client,
            @Value("${harness.temporal.task-queue:coding-v1}") String taskQueue) {
        this.client = Objects.requireNonNull(client);
        this.taskQueue = Objects.requireNonNull(taskQueue);
    }

    @Override public StartReceipt start(RunSpec spec) {
        Objects.requireNonNull(spec);
        var options = WorkflowOptions.newBuilder().setWorkflowId(spec.runId()).setTaskQueue(taskQueue)
                .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setMemo(Map.of("inputSha256", spec.inputSha256()))
                .setWorkflowExecutionTimeout(Duration.ofDays(8)).build();
        var workflow = client.newWorkflowStub(CodingWorkflow.class, options);
        try {
            WorkflowClient.start(workflow::run, spec);
            return new StartReceipt(StartOutcome.STARTED, spec.inputSha256());
        } catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
            var existing = describe(spec.runId());
            if (existing == null || existing.inputSha256() == null)
                throw new IllegalStateException("Existing workflow identity unavailable", alreadyStarted);
            return new StartReceipt(StartOutcome.ALREADY_EXISTS, existing.inputSha256());
        }
    }

    @Override public CommandResult cancel(String runId, CancelCommand command) {
        WorkflowDescription described = describe(runId);
        if (described == null) throw new IllegalArgumentException("Unknown workflow");
        if (terminal(described.status()))
            return new CommandResult(runId, command.commandId(), "REJECTED_TERMINAL");
        try {
            return client.newWorkflowStub(CodingWorkflow.class, runId).cancel(command);
        } catch (WorkflowNotFoundException closedDuringUpdate) {
            WorkflowDescription latest = describe(runId);
            if (latest != null && terminal(latest.status()))
                return new CommandResult(runId, command.commandId(), "REJECTED_TERMINAL");
            throw closedDuringUpdate;
        }
    }

    @Override public WorkflowDescription describe(String runId) {
        try {
            var description = client.newUntypedWorkflowStub(runId).describe();
            String input = (String) description.getMemo("inputSha256", String.class);
            RunView view = null;
            try {
                view = client.newWorkflowStub(CodingWorkflow.class, runId).current();
            } catch (RuntimeException unavailable) {
                // Memo identity remains readable while the workflow query is temporarily unavailable.
            }
            return view == null
                    ? new WorkflowDescription(runId, input, 0, description.getStatus().name(), null, null)
                    : new WorkflowDescription(runId, input, view.version(), view.status(),
                            view.snapshotSha256(), view.terminalResultBlobId());
        } catch (WorkflowNotFoundException missing) {
            return null;
        }
    }

    private static boolean terminal(String status) {
        return switch (status) {
            case "SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED", "LIMIT_EXHAUSTED",
                    "INFRASTRUCTURE_FAILED", "CONTEXT_OVERFLOW",
                    "WORKFLOW_EXECUTION_STATUS_COMPLETED", "WORKFLOW_EXECUTION_STATUS_FAILED",
                    "WORKFLOW_EXECUTION_STATUS_CANCELED", "WORKFLOW_EXECUTION_STATUS_TERMINATED",
                    "WORKFLOW_EXECUTION_STATUS_TIMED_OUT" -> true;
            default -> false;
        };
    }
}
