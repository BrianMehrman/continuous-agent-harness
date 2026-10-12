package com.brianmehrman.harness.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.brianmehrman.harness.execution.CodingWorkflow;
import com.brianmehrman.harness.execution.CancelCommand;
import com.brianmehrman.harness.execution.Limits;
import com.brianmehrman.harness.model.ProfileRevision;
import com.brianmehrman.harness.model.ProfileRevisionStore;
import com.brianmehrman.harness.runs.CommandDispatcher;
import com.brianmehrman.harness.runs.RunCommandService;
import com.brianmehrman.harness.runs.RunProjectionRepository;
import com.brianmehrman.harness.runs.StartCommand;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Crashes the real worker while its model Activity owns a durable attempt, then resumes that run. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "harness.api.dispatch-enabled=false")
@ActiveProfiles("api")
class CodingRunRecoveryIT {
    private static final String QUEUE = "coding-recovery-" + UUID.randomUUID();
    private static final String DEPLOYMENT = "coding-harness";
    private static final String NAMESPACE = "harness";
    private static final String SENTINEL = "task9-sentinel-credential-must-not-leak";
    private static final String TARGET = System.getenv().getOrDefault("HARNESS_TEMPORAL_TARGET", "127.0.0.1:7233");
    private static final String COMPOSE_PROJECT = System.getenv().getOrDefault(
            "HARNESS_COMPOSE_PROJECT", "continuous-agent-harness");

    @DynamicPropertySource
    static void temporalQueue(DynamicPropertyRegistry registry) {
        registry.add("harness.temporal.task-queue", () -> QUEUE);
    }

    @Autowired WorkflowClient client;
    @Autowired RunCommandService commands;
    @Autowired CommandDispatcher dispatcher;
    @Autowired ProfileRevisionStore profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired RunProjectionRepository projections;

    @Test
    void workerDeathDuringModelCallResumesSameRunAndRecordsBoundedAttemptReceipts() throws Exception {
        String build = "sha256:" + UUID.randomUUID().toString().replace("-", "") + "a".repeat(32);
        String previousCurrent = currentBuildId();
        boolean changedRouting = false;
        String profile = "task9-recovery-profile-" + UUID.randomUUID();
        profiles.save(new ProfileRevision(profile, "ollama", "http://127.0.0.1:11434", "fixture-local",
                "a".repeat(64), 8192, 1024, 0, true, SENTINEL));

        String invocationMarker = "task9-model-call-entered-" + UUID.randomUUID();
        Path marker = Path.of("build", invocationMarker);
        Path logs = Files.createTempDirectory(Path.of("build"), "task9-coding-recovery-");
        Process firstWorker = null;
        Process restartedWorker = null;
        try {
            firstWorker = startWorker(build, marker, logs.resolve("worker-first.log"));
            awaitProcessAlive(firstWorker);
            awaitBuildRegistered(build);
            setCurrentBuild(build);
            changedRouting = true;

            var limits = new Limits(2, 20, 4, 2, 2, 8_000, 120_000, 180_000, 10_000, 120_000);
            var accepted = commands.start(new StartCommand("task9-recovery-" + UUID.randomUUID(),
                    "task-tracker-v1", profile, limits));
            String runId = accepted.runId();
            String seedSnapshot = projections.view(runId).snapshotSha256();
            assertThat(dispatcher.dispatchOnce(runId)).isTrue();

            await(Duration.ofSeconds(30), () -> Files.exists(marker) && attemptStatus(runId,
                    runId + ":turn-1", 1).equals("IN_FLIGHT"));
            firstWorker.destroyForcibly();
            assertThat(firstWorker.waitFor(10, TimeUnit.SECONDS)).isTrue();

            restartedWorker = startWorker(build, marker, logs.resolve("worker-restarted.log"));
            awaitProcessAlive(restartedWorker);
            await(Duration.ofSeconds(60), () -> "FAILED".equals(projections.view(runId).status()));

            var result = WorkflowStub.fromTyped(client.newWorkflowStub(CodingWorkflow.class, runId))
                    .getResult(10, TimeUnit.SECONDS, com.brianmehrman.harness.execution.RunView.class);
            assertThat(result.runId()).isEqualTo(runId);
            assertThat(result.status()).isEqualTo("FAILED");

            var projection = projections.view(runId);
            assertThat(projection.runId()).isEqualTo(runId);
            assertThat(projection.snapshotSha256()).isEqualTo(seedSnapshot);
            assertThat(projection.stale()).isFalse();
            assertThat(projection.lastSequence()).isGreaterThan(0);

            assertThat(jdbc.queryForObject("select delivered_at is not null from run_command " +
                    "where run_id=? and kind='START'", Boolean.class, runId)).isTrue();
            assertThat(attemptStatus(runId, runId + ":turn-1", 1)).isEqualTo("UNKNOWN");
            assertThat(attemptStatus(runId, runId + ":turn-1", 2)).isEqualTo("RECORDED");
            assertThat(attemptStatus(runId, runId + ":turn-2", 1)).isEqualTo("RECORDED");
            assertThat(jdbc.queryForObject("select count(*) from model_attempt where run_id=?",
                    Integer.class, runId)).isEqualTo(3);
            stop(restartedWorker);
            assertThat(Files.readString(logs.resolve("worker-first.log"))).doesNotContain(SENTINEL);
            assertThat(Files.readString(logs.resolve("worker-restarted.log"))).doesNotContain(SENTINEL);
            assertThat(new String(client.fetchHistory(runId).getHistory().toByteArray(),
                    java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(SENTINEL);
        } finally {
            if (restartedWorker != null && restartedWorker.isAlive()) stop(restartedWorker);
            if (firstWorker != null && firstWorker.isAlive()) stop(firstWorker);
            Files.deleteIfExists(marker);
            if (changedRouting) restoreCurrentBuild(previousCurrent);
        }
    }

    @Test
    void deadlineExpiresWhileTemporalIsDownWithoutStartingAnotherModelCall() throws Exception {
        String build = "sha256:" + UUID.randomUUID().toString().replace("-", "") + "b".repeat(32);
        String previousCurrent = currentBuildId();
        String profile = "task9-outage-profile-" + UUID.randomUUID();
        profiles.save(new ProfileRevision(profile, "ollama", "http://127.0.0.1:11434", "fixture-local",
                "a".repeat(64), 8192, 1024, 0, true, null));
        Path marker = Path.of("build", "task9-outage-model-entered-" + UUID.randomUUID());
        Path logs = Files.createTempDirectory(Path.of("build"), "task9-temporal-outage-");
        Process worker = null;
        boolean changedRouting = false;
        boolean temporalStopped = false;
        try {
            worker = startWorker(build, marker, logs.resolve("worker-first.log"));
            awaitProcessAlive(worker);
            awaitBuildRegistered(build);
            setCurrentBuild(build);
            changedRouting = true;

            var limits = new Limits(2, 20, 4, 2, 2, 30_000, 120_000, 180_000, 10_000, 8_000);
            var accepted = commands.start(new StartCommand("task9-outage-" + UUID.randomUUID(),
                    "task-tracker-v1", profile, limits));
            String runId = accepted.runId();
            String seedSnapshot = projections.view(runId).snapshotSha256();
            assertThat(dispatcher.dispatchOnce(runId)).isTrue();
            await(Duration.ofSeconds(30), () -> Files.exists(marker) && "IN_FLIGHT".equals(
                    attemptStatus(runId, runId + ":turn-1", 1)));
            long deadline = System.nanoTime() + Duration.ofMillis(limits.runMillis()).toNanos();

            compose("stop", "temporal");
            temporalStopped = true;
            stop(worker);
            while (System.nanoTime() < deadline) Thread.sleep(100);
            compose("up", "-d", "--wait", "temporal");
            temporalStopped = false;

            worker = startWorker(build, marker, logs.resolve("worker-restarted.log"));
            awaitProcessAlive(worker);
            await(Duration.ofSeconds(60), () -> "EXPIRED".equals(projections.view(runId).status()));
            var result = WorkflowStub.fromTyped(client.newWorkflowStub(CodingWorkflow.class, runId))
                    .getResult(10, TimeUnit.SECONDS, com.brianmehrman.harness.execution.RunView.class);
            assertThat(result.runId()).isEqualTo(runId);
            assertThat(result.status()).isEqualTo("EXPIRED");
            assertThat(projections.view(runId).snapshotSha256()).isEqualTo(seedSnapshot);
            assertThat(jdbc.queryForObject("select count(*) from model_attempt where run_id=?",
                    Integer.class, runId)).isEqualTo(1);
        } finally {
            if (temporalStopped) compose("up", "-d", "--wait", "temporal");
            if (worker != null && worker.isAlive()) stop(worker);
            Files.deleteIfExists(marker);
            if (changedRouting) restoreCurrentBuild(previousCurrent);
        }
    }

    @Test
    void cancellationQueuedWhileWorkerIsAbsentStopsFurtherModelCallsAfterDelivery() throws Exception {
        String build = "sha256:" + UUID.randomUUID().toString().replace("-", "") + "c".repeat(32);
        String previousCurrent = currentBuildId();
        String profile = "task9-cancel-profile-" + UUID.randomUUID();
        profiles.save(new ProfileRevision(profile, "ollama", "http://127.0.0.1:11434", "fixture-local",
                "a".repeat(64), 8192, 1024, 0, true, null));
        Path marker = Path.of("build", "task9-cancel-model-entered-" + UUID.randomUUID());
        Path logs = Files.createTempDirectory(Path.of("build"), "task9-cancel-absent-worker-");
        Process worker = null;
        boolean changedRouting = false;
        var delivery = Executors.newSingleThreadExecutor();
        try {
            worker = startWorker(build, marker, logs.resolve("worker-first.log"));
            awaitProcessAlive(worker);
            awaitBuildRegistered(build);
            setCurrentBuild(build);
            changedRouting = true;

            var limits = new Limits(2, 20, 4, 2, 2, 30_000, 120_000, 180_000, 10_000, 120_000);
            var accepted = commands.start(new StartCommand("task9-cancel-" + UUID.randomUUID(),
                    "task-tracker-v1", profile, limits));
            String runId = accepted.runId();
            String seedSnapshot = projections.view(runId).snapshotSha256();
            assertThat(dispatcher.dispatchOnce(runId)).isTrue();
            await(Duration.ofSeconds(30), () -> Files.exists(marker) && "IN_FLIGHT".equals(
                    attemptStatus(runId, runId + ":turn-1", 1)));

            stop(worker);
            String cancelId = "task9-cancel-command-" + UUID.randomUUID();
            assertThat(commands.cancel(runId, new CancelCommand(cancelId, projections.view(runId).version()))
                    .deliveryStatus()).isEqualTo("ACCEPTED");
            var pendingDelivery = delivery.submit(() -> dispatcher.dispatchOnce(runId));
            await(Duration.ofSeconds(10), () -> jdbc.queryForObject(
                    "select lease_owner is not null from run_command where command_id=?",
                    Boolean.class, cancelId));
            assertThat(projections.view(runId).status()).isEqualTo("RUNNING");

            worker = startWorker(build, marker, logs.resolve("worker-restarted.log"));
            awaitProcessAlive(worker);
            assertThat(pendingDelivery.get(45, TimeUnit.SECONDS)).isTrue();
            int attemptsAtDelivery = jdbc.queryForObject(
                    "select count(*) from model_attempt where run_id=?", Integer.class, runId);
            await(Duration.ofSeconds(45), () -> "CANCELLED".equals(projections.view(runId).status()));
            assertThat(projections.view(runId).snapshotSha256()).isEqualTo(seedSnapshot);
            assertThat(attemptsAtDelivery).isBetween(1, 2);
            assertThat(jdbc.queryForObject("select count(*) from model_attempt where run_id=?",
                    Integer.class, runId)).isEqualTo(attemptsAtDelivery);
        } finally {
            delivery.shutdownNow();
            if (worker != null && worker.isAlive()) stop(worker);
            Files.deleteIfExists(marker);
            if (changedRouting) restoreCurrentBuild(previousCurrent);
        }
    }

    private void compose(String... args) throws Exception {
        var command = new ArrayList<>(List.of("docker", "compose", "-p", COMPOSE_PROJECT));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0)
            throw new AssertionError("Compose command failed: " + new String(output));
    }

    private String attemptStatus(String runId, String invocationId, int attempt) {
        var rows = jdbc.queryForList("select status from model_attempt where run_id=? and invocation_id=? and attempt=?",
                String.class, runId, invocationId, attempt);
        return rows.isEmpty() ? "MISSING" : rows.getFirst();
    }

    private Process startWorker(String build, Path marker, Path output) throws Exception {
        String classpath = System.getProperty("runner.test.classpath");
        if (classpath == null || classpath.isBlank())
            throw new IllegalStateException("Gradle test runtime classpath is required");
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, CodingRecoveryWorkerProcess.class.getName(),
                "--spring.profiles.active=worker,recovery-test",
                "--harness.temporal.target=" + TARGET,
                "--harness.temporal.namespace=" + NAMESPACE,
                "--harness.temporal.task-queue=" + QUEUE,
                "--harness.temporal.build-id=" + build,
                "--task9.recovery.marker=" + marker.toAbsolutePath()));
        var builder = new ProcessBuilder(command).directory(Path.of(".").toAbsolutePath().toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("HARNESS_SENTINEL_CREDENTIAL", SENTINEL);
        return builder.start();
    }

    private void awaitProcessAlive(Process process) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) Thread.sleep(50);
        assertThat(process.isAlive()).as("worker JVM remains alive").isTrue();
    }

    private void await(Duration timeout, java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Recovery condition did not become true within " + timeout);
    }

    private String currentBuildId() throws Exception {
        var routing = deploymentInfo().path("routingConfig");
        if (!DEPLOYMENT.equals(routing.path("currentVersionDeploymentName").asText())) return null;
        return routing.path("currentVersionBuildID").asText(null);
    }

    private void awaitBuildRegistered(String build) throws Exception {
        await(Duration.ofSeconds(45), () -> {
            try {
                return deploymentInfo().path("versionSummaries").toString().contains(build);
            } catch (Exception ignored) {
                return false;
            }
        });
    }

    private void setCurrentBuild(String build) throws Exception {
        temporalCli("worker", "deployment", "set-current-version", "--deployment-name", DEPLOYMENT,
                "--build-id", build, "--yes");
    }

    private void restoreCurrentBuild(String build) throws Exception {
        if (build == null) temporalCli("worker", "deployment", "set-current-version",
                "--deployment-name", DEPLOYMENT, "--unversioned", "--allow-no-pollers", "--yes");
        else setCurrentBuild(build);
    }

    private tools.jackson.databind.JsonNode deploymentInfo() throws Exception {
        String output = temporalCli("worker", "deployment", "describe", "--name", DEPLOYMENT,
                "--output", "json");
        int start = output.indexOf('{');
        if (start < 0) throw new AssertionError("Temporal CLI returned no deployment JSON");
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(output.substring(start));
    }

    private String temporalCli(String... args) throws Exception {
        var command = new ArrayList<>(List.of("docker", "compose", "-p", COMPOSE_PROJECT, "run", "--rm",
                "--no-deps", "--entrypoint", "temporal", "temporal-namespace", "--address",
                "temporal:7233", "--namespace", NAMESPACE));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0)
            throw new AssertionError("Temporal CLI command failed: " + new String(output));
        return new String(output);
    }

    private static void stop(Process process) {
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
