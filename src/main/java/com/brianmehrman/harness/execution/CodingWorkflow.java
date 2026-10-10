package com.brianmehrman.harness.execution;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface CodingWorkflow {
    @WorkflowMethod RunView run(RunSpec spec);
    @UpdateMethod CommandResult cancel(CancelCommand command);
    @QueryMethod RunView current();
}
