package com.brianmehrman.harness.recovery;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.worker.WorkerDeploymentOptions;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import java.util.concurrent.CountDownLatch;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;

/** Child process used by DeploymentIT to prove server-routed version affinity across restarts. */
public final class DeploymentVersionWorkerProcess {
    private DeploymentVersionWorkerProcess() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("target, namespace, queue, build ID required");
        var service = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(args[0]).build());
        var client = WorkflowClient.newInstance(service,
                WorkflowClientOptions.newBuilder().setNamespace(args[1]).build());
        var factory = WorkerFactory.newInstance(client);
        var options = WorkerOptions.newBuilder().setDeploymentOptions(
                WorkerDeploymentOptions.newBuilder().setUseVersioning(true)
                        .setVersion(new WorkerDeploymentVersion("coding-harness", args[3])).build()).build();
        var worker = factory.newWorker(args[2], options);
        worker.registerWorkflowImplementationTypes(DeploymentProbeWorkflowImpl.class);
        worker.registerActivitiesImplementations((DeploymentProbeWorkflowImpl.DeploymentProbeActivities)
                runLabel -> args[3]);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            factory.shutdownNow();
            service.shutdown();
        }));
        factory.start();
        new CountDownLatch(1).await();
    }
}
