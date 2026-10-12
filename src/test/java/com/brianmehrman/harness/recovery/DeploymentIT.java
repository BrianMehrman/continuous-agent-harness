package com.brianmehrman.harness.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Starts actual worker JVMs to verify Temporal's pinned deployment routing and rollback. */
class DeploymentIT {
    private static final String DEPLOYMENT = "coding-harness";
    private static final String NAMESPACE = "harness";
    private static final String TARGET = System.getenv().getOrDefault(
            "HARNESS_TEMPORAL_TARGET", "127.0.0.1:7233");
    private static final String COMPOSE_PROJECT = System.getenv().getOrDefault(
            "HARNESS_COMPOSE_PROJECT", "continuous-agent-harness");
    private static final String QUEUE = "deployment-it-" + UUID.randomUUID();
    private static final Duration BARRIER_TIMEOUT = Duration.ofSeconds(45);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static WorkflowClient client;
    private static WorkflowServiceStubs temporal;

    @BeforeAll
    static void connectToTemporal() {
        temporal = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(TARGET).build());
        client = WorkflowClient.newInstance(temporal,
                WorkflowClientOptions.newBuilder().setNamespace(NAMESPACE).build());
    }

    @AfterAll
    static void closeTemporal() {
        if (temporal != null) temporal.shutdown();
    }

    @Test
    void pinnedRunsStayOnTheirVersionThroughForwardDeploymentRestartAndRollback() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String v1 = "sha256:" + suffix + "1".repeat(64 - suffix.length());
        String v2 = "sha256:" + UUID.randomUUID().toString().replace("-", "") + "2".repeat(32);
        String originalCurrent = currentBuildId();
        Path logs = Files.createTempDirectory(Path.of("build"), "task9-deployment-");
        var processes = new ArrayList<Process>();
        try {
            Process v1Worker = startWorker(v1, logs.resolve("v1-first.log"));
            processes.add(v1Worker);
            awaitProcessStarted(v1Worker);
            awaitVersionRegistered(v1);
            setCurrentVersion(v1);
            awaitCurrentVersion(v1);

            DeploymentProbeWorkflow runA = start("run-a");
            String runAId = workflowId(runA);
            awaitReady(runA);

            Process v2Worker = startWorker(v2, logs.resolve("v2.log"));
            processes.add(v2Worker);
            awaitProcessStarted(v2Worker);
            awaitVersionRegistered(v2);
            setCurrentVersion(v2);
            awaitCurrentVersion(v2);
            DeploymentProbeWorkflow runB = start("run-b");
            String runBId = workflowId(runB);
            awaitReady(runB);

            stop(v1Worker);
            runA.release();
            assertThat(client.newUntypedWorkflowStub(workflowId(runA)).describe()
                    .getStatus().name()).isEqualTo("WORKFLOW_EXECUTION_STATUS_RUNNING");

            v1Worker = startWorker(v1, logs.resolve("v1-restarted.log"));
            processes.add(v1Worker);
            awaitProcessStarted(v1Worker);
            awaitCurrentVersion(v2);
            assertThat(WorkflowStub.fromTyped(client.newWorkflowStub(
                    DeploymentProbeWorkflow.class, runAId)).getResult(45, TimeUnit.SECONDS, String.class))
                    .isEqualTo(v1);
            runB.release();
            assertThat(WorkflowStub.fromTyped(client.newWorkflowStub(
                    DeploymentProbeWorkflow.class, runBId)).getResult(45, TimeUnit.SECONDS, String.class))
                    .isEqualTo(v2);

            setCurrentVersion(v1);
            awaitCurrentVersion(v1);
            DeploymentProbeWorkflow runC = start("run-c");
            String runCId = workflowId(runC);
            awaitReady(runC);
            runC.release();
            assertThat(WorkflowStub.fromTyped(client.newWorkflowStub(
                    DeploymentProbeWorkflow.class, runCId)).getResult(45, TimeUnit.SECONDS, String.class))
                    .isEqualTo(v1);
        } finally {
            if (originalCurrent == null) setUnversionedCurrent();
            else setCurrentVersion(originalCurrent);
            for (Process process : processes) stop(process);
        }
    }

    private DeploymentProbeWorkflow start(String label) {
        String id = "deployment-" + label + "-" + UUID.randomUUID();
        var workflow = client.newWorkflowStub(DeploymentProbeWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(id).setTaskQueue(QUEUE)
                        .setWorkflowExecutionTimeout(Duration.ofMinutes(3)).build());
        WorkflowClient.start(workflow::waitForRelease, label);
        return workflow;
    }

    private static String workflowId(DeploymentProbeWorkflow workflow) {
        return WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId();
    }

    private void awaitReady(DeploymentProbeWorkflow workflow) throws Exception {
        long deadline = System.nanoTime() + BARRIER_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (workflow.isReady()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Workflow did not reach its persisted ready query before timeout: " +
                workflowId(workflow));
    }

    private void awaitCurrentVersion(String buildId) throws Exception {
        long deadline = System.nanoTime() + BARRIER_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (buildId.equals(currentBuildId())) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Temporal did not make worker build current: " + buildId +
                "; observed current build=" + currentBuildId());
    }

    private void awaitVersionRegistered(String buildId) throws Exception {
        long deadline = System.nanoTime() + BARRIER_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            var summaries = deploymentInfo().path("versionSummaries");
            for (var summary : summaries) {
                if (DEPLOYMENT.equals(summary.path("deploymentName").asText()) &&
                        buildId.equals(summary.path("BuildID").asText())) return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Worker build did not register with Temporal: " + buildId);
    }

    private void setCurrentVersion(String buildId) throws Exception {
        runTemporalCli(List.of(
                "worker", "deployment", "set-current-version", "--deployment-name", DEPLOYMENT,
                "--build-id", buildId, "--yes"));
    }

    private void setUnversionedCurrent() throws Exception {
        runTemporalCli(List.of("worker", "deployment", "set-current-version",
                "--deployment-name", DEPLOYMENT, "--unversioned", "--allow-no-pollers", "--yes"));
    }

    private String runTemporalCli(List<String> temporalArgs) throws Exception {
        var command = new ArrayList<>(List.of("docker", "compose", "-p", COMPOSE_PROJECT,
                "run", "--rm", "--no-deps", "--entrypoint", "temporal", "temporal-namespace",
                "--address", "temporal:7233", "--namespace", NAMESPACE));
        command.addAll(temporalArgs);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Temporal deployment CLI timed out");
        }
        String text = new String(output);
        if (process.exitValue() != 0)
            throw new AssertionError("Temporal deployment CLI failed: " + text);
        return text;
    }

    private String currentBuildId() {
        try {
            var current = deploymentInfo().path("routingConfig");
            if (!DEPLOYMENT.equals(current.path("currentVersionDeploymentName").asText())) return null;
            return current.path("currentVersionBuildID").asText(null);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to read Temporal worker deployment state", error);
        }
    }

    private tools.jackson.databind.JsonNode deploymentInfo() throws Exception {
        String output = runTemporalCli(List.of("worker", "deployment", "describe",
                "--name", DEPLOYMENT, "--output", "json"));
        int jsonStart = output.indexOf('{');
        if (jsonStart < 0) throw new AssertionError("Deployment CLI returned no JSON object: " + output);
        return JSON.readTree(output.substring(jsonStart));
    }

    private static Process startWorker(String buildId, Path log) throws IOException {
        String classpath = System.getProperty("runner.test.classpath");
        if (classpath == null || classpath.isBlank())
            throw new IllegalStateException("Gradle test runtime classpath is required");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return new ProcessBuilder(java.toString(), "-cp", classpath,
                DeploymentVersionWorkerProcess.class.getName(), TARGET, NAMESPACE, QUEUE, buildId)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static void awaitProcessStarted(Process process) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(process.isAlive()).as("worker JVM remains running").isTrue();
    }

    private static void stop(Process process) {
        if (!process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
