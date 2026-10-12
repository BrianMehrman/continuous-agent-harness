package com.brianmehrman.harness.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Verifies a persisted pinned workflow resumes on the same build after its worker process dies. */
class RecoveryIT {
    @Test
    void pinnedWorkflowResumesAfterWorkerJvmRestart() throws Exception {
        String target = System.getenv().getOrDefault("HARNESS_TEMPORAL_TARGET", "127.0.0.1:7233");
        String project = System.getenv().getOrDefault("HARNESS_COMPOSE_PROJECT", "continuous-agent-harness");
        String build = "sha256:" + UUID.randomUUID().toString().replace("-", "") + "9".repeat(32);
        String queue = "recovery-it-" + UUID.randomUUID();
        String id = "recovery-" + UUID.randomUUID();
        String previous = currentBuild(project);
        Path logs = Files.createTempDirectory(Path.of("build"), "task9-recovery-");
        var temporal = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
        var client = WorkflowClient.newInstance(temporal,
                WorkflowClientOptions.newBuilder().setNamespace("harness").build());
        Process worker = null;
        try {
            worker = startWorker(target, build, queue, logs.resolve("worker-first.log"));
            awaitAlive(worker);
            awaitRegistered(project, build);
            cli(project, "worker", "deployment", "set-current-version", "--deployment-name",
                    "coding-harness", "--build-id", build, "--yes");
            var workflow = client.newWorkflowStub(DeploymentProbeWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId(id).setTaskQueue(queue)
                            .setWorkflowExecutionTimeout(Duration.ofMinutes(2)).build());
            WorkflowClient.start(workflow::waitForRelease, "recovery");
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (!workflow.isReady() && System.nanoTime() < deadline) Thread.sleep(100);
            assertThat(workflow.isReady()).isTrue();
            worker.destroy();
            assertThat(worker.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(client.newUntypedWorkflowStub(id).describe().getStatus().name())
                    .isEqualTo("WORKFLOW_EXECUTION_STATUS_RUNNING");

            worker = startWorker(target, build, queue, logs.resolve("worker-restarted.log"));
            awaitAlive(worker);
            workflow.release();
            assertThat(WorkflowStub.fromTyped(workflow).getResult(45, TimeUnit.SECONDS, String.class))
                    .isEqualTo(build);
            assertThat(client.newUntypedWorkflowStub(id).describe().getStatus().name())
                    .isEqualTo("WORKFLOW_EXECUTION_STATUS_COMPLETED");
        } finally {
            if (previous == null) {
                cli(project, "worker", "deployment", "set-current-version", "--deployment-name",
                        "coding-harness", "--unversioned", "--allow-no-pollers",
                        "--ignore-missing-task-queues", "--yes");
            } else {
                cli(project, "worker", "deployment", "set-current-version", "--deployment-name",
                        "coding-harness", "--build-id", previous, "--allow-no-pollers",
                        "--ignore-missing-task-queues", "--yes");
            }
            if (worker != null && worker.isAlive()) {
                worker.destroy();
                if (!worker.waitFor(10, TimeUnit.SECONDS)) worker.destroyForcibly();
            }
            temporal.shutdown();
        }
    }

    private static Process startWorker(String target, String build, String queue, Path log) throws Exception {
        String cp = System.getProperty("runner.test.classpath");
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", cp, DeploymentVersionWorkerProcess.class.getName(), target, "harness", queue, build)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static void awaitAlive(Process worker) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (worker.isAlive() && System.nanoTime() < deadline) Thread.sleep(50);
        assertThat(worker.isAlive()).isTrue();
    }

    private static String currentBuild(String project) throws Exception {
        String output = cli(project, "worker", "deployment", "describe", "--name", "coding-harness", "--output", "json");
        int start = output.indexOf('{');
        if (start < 0) return null;
        var node = tools.jackson.databind.json.JsonMapper.builder().build().readTree(output.substring(start))
                .path("routingConfig");
        if (!"coding-harness".equals(node.path("currentVersionDeploymentName").asText())) return null;
        return node.path("currentVersionBuildID").asText(null);
    }

    private static void awaitRegistered(String project, String build) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            String output = cli(project, "worker", "deployment", "describe", "--name", "coding-harness", "--output", "json");
            int start = output.indexOf('{');
            if (start >= 0 && tools.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(output.substring(start)).path("versionSummaries").toString().contains(build)) return;
            Thread.sleep(100);
        }
        throw new AssertionError("worker build did not register: " + build);
    }

    private static String cli(String project, String... args) throws Exception {
        var command = new java.util.ArrayList<>(java.util.List.of("docker", "compose", "-p", project,
                "run", "--rm", "--no-deps", "--entrypoint", "temporal", "temporal-namespace",
                "--address", "temporal:7233", "--namespace", "harness"));
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0)
            throw new AssertionError("Temporal CLI failed: " + output);
        return output;
    }
}
