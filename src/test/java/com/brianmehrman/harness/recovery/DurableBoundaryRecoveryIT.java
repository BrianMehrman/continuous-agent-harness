package com.brianmehrman.harness.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.brianmehrman.harness.model.ProfileRevision;
import com.brianmehrman.harness.model.ProfileRevisionStore;
import com.brianmehrman.harness.runs.BlobStore;
import com.brianmehrman.harness.runs.CommandDispatcher;
import com.brianmehrman.harness.runs.RunEvent;
import com.brianmehrman.harness.runs.RunCommandService;
import com.brianmehrman.harness.runs.RunProjectionRepository;
import com.brianmehrman.harness.runs.StartCommand;
import com.brianmehrman.harness.execution.Limits;
import com.brianmehrman.harness.workspace.SnapshotRef;
import com.brianmehrman.harness.workspace.WorkspaceStore;
import com.brianmehrman.harness.workspace.WriteRequest;
import io.temporal.client.WorkflowClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** Kills a separate JVM at three persisted receipt/acknowledgment boundaries. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "harness.api.dispatch-enabled=false")
@ActiveProfiles("api")
class DurableBoundaryRecoveryIT {
    private static final String QUEUE = "durable-boundary-" + UUID.randomUUID();
    private static final String TARGET = System.getenv().getOrDefault("HARNESS_TEMPORAL_TARGET", "127.0.0.1:7233");
    private final List<Process> children = new ArrayList<>();
    @DynamicPropertySource static void queue(DynamicPropertyRegistry registry) {
        registry.add("harness.temporal.task-queue", () -> QUEUE);
    }

    @Autowired WorkspaceStore workspaces;
    @Autowired ProfileRevisionStore profiles;
    @Autowired RunCommandService commands;
    @Autowired CommandDispatcher dispatcher;
    @Autowired RunProjectionRepository projections;
    @Autowired BlobStore blobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkflowClient client;

    @AfterEach void stopChildren() throws Exception {
        for (Process child : children) if (child.isAlive()) {
            child.destroyForcibly();
            child.waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test void committedWorkspaceReceiptSurvivesDeathBeforeCallerAcknowledgment() throws Exception {
        String runId = "source-receipt-" + UUID.randomUUID();
        SnapshotRef parent = workspaces.seed(runId, "task-tracker-v1");
        String invocation = "write-" + UUID.randomUUID();
        var request = new WriteRequest(runId, invocation, parent,
                "src/test/java/ProcessProbeTest.java", "absent", "class ProcessProbeTest {}");
        Path marker = marker();
        Process first = launch("source", marker, runId, parent.sha256(), invocation);
        await(() -> Files.exists(marker));
        kill(first);

        SnapshotRef replay = workspaces.write(request);
        assertThat(workspaces.write(request)).isEqualTo(replay);
        assertThat(jdbc.queryForObject("select count(*) from workspace_write_receipt " +
                "where run_id=? and invocation_id=?", Integer.class, runId, invocation)).isEqualTo(1);
        assertThat(workspaces.files(runId, replay).get(request.path())).isEqualTo(request.content());
    }

    @Test void temporalStartSurvivesDeathBeforeOutboxAcknowledgment() throws Exception {
        String runId = newRun();
        Path marker = marker();
        Process first = launch("command", marker, runId);
        try {
            await(() -> Files.exists(marker));
            kill(first);
            assertThat(jdbc.queryForObject("select delivered_at from run_command where run_id=?",
                    java.sql.Timestamp.class, runId)).isNull();
            await(() -> Boolean.TRUE.equals(jdbc.queryForObject("select lease_until<now() " +
                    "from run_command where run_id=?", Boolean.class, runId)), Duration.ofSeconds(45));
            long reconciliationStarted = System.nanoTime();
            assertThat(dispatcher.dispatchOnce(runId)).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - reconciliationStarted))
                    .isLessThan(Duration.ofSeconds(20));
            assertThat(jdbc.queryForObject("select delivered_at from run_command where run_id=?",
                    java.sql.Timestamp.class, runId)).isNotNull();
            assertThat(jdbc.queryForObject("select attempt_count from run_command where run_id=?",
                    Integer.class, runId)).isEqualTo(2);
            assertThat(client.newUntypedWorkflowStub(runId).describe().getWorkflowExecutionInfo()
                    .getExecution().getWorkflowId()).isEqualTo(runId);
        } finally {
            client.newUntypedWorkflowStub(runId).terminate("test cleanup");
        }
    }

    @Test void projectionTransactionRollsBackAfterProcessDeathAndReplaysOnce() throws Exception {
        String runId = newRun();
        String initialSnapshot = projections.view(runId).snapshotSha256();
        String payload = blobs.put("application/vnd.harness.run-event+json",
                JsonMapper.builder().build().writeValueAsBytes(java.util.Map.of(
                        "version", 1, "status", "RUNNING", "snapshotSha256", initialSnapshot)));
        String eventId = "projection-" + UUID.randomUUID();
        RunEvent event = new RunEvent(runId, 1, eventId, "STATE_CHANGED", payload);
        Path marker = marker();
        Process first = launch("projection", marker, runId, eventId, payload);
        await(() -> Files.exists(marker));
        kill(first);

        assertThat(jdbc.queryForObject("select count(*) from run_event where event_id=?",
                Integer.class, eventId)).isZero();
        assertThat(projections.view(runId).status()).isEqualTo("QUEUED");
        assertThat(projections.append(event).status()).isEqualTo("RUNNING");
        assertThat(projections.append(event).snapshotSha256()).isEqualTo(initialSnapshot);
        assertThat(jdbc.queryForObject("select count(*) from run_event where event_id=?",
                Integer.class, eventId)).isEqualTo(1);
    }

    private String newRun() {
        String profile = "boundary-profile-" + UUID.randomUUID();
        profiles.save(new ProfileRevision(profile, "ollama", "http://127.0.0.1:11434",
                "fixture-local", "a".repeat(64), 8192, 1024, 0, true, null));
        return commands.start(new StartCommand("boundary-start-" + UUID.randomUUID(),
                "task-tracker-v1", profile, Limits.codingDefaults())).runId();
    }

    private Path marker() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("build"), "task9-boundary-");
        return directory.resolve("reached.marker");
    }

    private Process launch(String mode, Path marker, String... arguments) throws Exception {
        var argv = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("runner.test.classpath"), DurableBoundaryProcess.class.getName(),
                mode, marker.toAbsolutePath().toString()));
        argv.addAll(List.of(arguments));
        argv.addAll(List.of("--spring.profiles.active=api", "--spring.main.web-application-type=none",
                "--harness.api.dispatch-enabled=false", "--harness.temporal.target=" + TARGET,
                "--harness.temporal.task-queue=" + QUEUE));
        Process child = new ProcessBuilder(argv).redirectErrorStream(true)
                .redirectOutput(marker.resolveSibling("child.log").toFile()).start();
        children.add(child);
        return child;
    }

    private static void kill(Process process) throws Exception {
        process.destroyForcibly();
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        await(condition, Duration.ofSeconds(30));
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("process boundary was not reached within " + timeout);
    }
}
