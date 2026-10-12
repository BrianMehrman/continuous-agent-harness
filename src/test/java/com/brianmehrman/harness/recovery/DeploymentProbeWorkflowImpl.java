package com.brianmehrman.harness.recovery;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.VersioningBehavior;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowVersioningBehavior;
import java.time.Duration;

public class DeploymentProbeWorkflowImpl implements DeploymentProbeWorkflow {
    private final DeploymentProbeActivities activities = Workflow.newActivityStub(
            DeploymentProbeActivities.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());
    private boolean ready;
    private boolean released;

    @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
    @Override public String waitForRelease(String runLabel) {
        ready = true;
        Workflow.await(() -> released);
        return activities.reportBuild(runLabel);
    }

    @Override public void release() { released = true; }
    @Override public boolean isReady() { return ready; }

    @ActivityInterface
    public interface DeploymentProbeActivities {
        @ActivityMethod String reportBuild(String runLabel);
    }
}
