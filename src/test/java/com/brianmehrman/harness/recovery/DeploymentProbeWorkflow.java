package com.brianmehrman.harness.recovery;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.WorkflowVersioningBehavior;

@WorkflowInterface
public interface DeploymentProbeWorkflow {
    @WorkflowMethod
    String waitForRelease(String runLabel);

    @SignalMethod void release();
    @QueryMethod boolean isReady();
}
