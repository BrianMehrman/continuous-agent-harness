package com.brianmehrman.harness.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.brianmehrman.harness.workspace.WorkspaceStore;
import com.brianmehrman.harness.workspace.WriteRequest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "harness.runner.enabled=true")
@ActiveProfiles("runner")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunnerIT {
    private static final String SCHEMA = "runner_it_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String base = System.getenv().getOrDefault("HARNESS_DB_URL",
                "jdbc:postgresql://127.0.0.1:55432/harness");
        registry.add("spring.datasource.url", () -> base + (base.contains("?") ? "&" : "?")
                + "currentSchema=" + SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
    }

    @Autowired Runner runner;
    @Autowired RunnerSupervisor supervisor;
    @Autowired WorkspaceStore workspaces;
    @Autowired JdbcTemplate jdbc;

    @AfterAll void removeTestSchema() {
        supervisor.shutdown();
        jdbc.execute("drop schema if exists " + SCHEMA + " cascade");
    }

    private InvocationRequest request(Operation operation, String source) {
        String run = "runner-feature-" + UUID.randomUUID();
        var snapshot = workspaces.seed(run, "task-tracker-v1");
        if (source != null) {
            snapshot = workspaces.write(new WriteRequest(run, "fixture", snapshot,
                    "src/test/java/ProbeTest.java", "absent", source));
        }
        return new InvocationRequest(run, UUID.randomUUID().toString(), snapshot, operation,
                System.currentTimeMillis() + 120_000);
    }

    private InvocationResult result(InvocationRequest request) throws Exception {
        runner.ensureStarted(request);
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < deadline) {
            var receipt = runner.result(request.invocationId());
            if (receipt.isPresent() && runner.stopConfirmed(request.invocationId())) return receipt.get();
            Thread.sleep(100);
        }
        return fail("Runner did not produce and clean a receipt within 120 seconds");
    }

    @Test void candidateTestsRunOfflineWithIsolationAndBoundedLogs() throws Exception {
        var request = request(Operation.TEST, """
                import org.junit.jupiter.api.Test;
                import java.nio.file.*; import java.net.*;
                import static org.junit.jupiter.api.Assertions.*;
                class ProbeTest { @Test void isolation() throws Exception {
                    assertFalse(Files.exists(Path.of("/var/run/docker.sock")));
                    assertFalse(Files.exists(Path.of("/Users/brianmehrman")));
                    assertFalse(Files.exists(Path.of("/.secrets")));
                    assertThrows(Exception.class, () -> Files.writeString(Path.of("/work/project/build.gradle"), "attack"));
                    assertThrows(Exception.class, () -> Files.writeString(Path.of("/control/ready"), "attack"));
                    assertThrows(Exception.class, () -> { try (var s = new Socket()) {
                        s.connect(new InetSocketAddress("1.1.1.1", 443), 500);
                    } });
                    assertEquals("128", Files.readString(Path.of("/sys/fs/cgroup/pids.max")).trim());
                    assertEquals("1073741824", Files.readString(Path.of("/sys/fs/cgroup/memory.max")).trim());
                    for (int i = 0; i < 10000; i++) System.out.println("bounded-output-".repeat(10));
                } }
                """);
        var receipt = result(request);
        assertThat(receipt.status()).isEqualTo("SUCCEEDED");
        assertThat(receipt.truncated()).isTrue();
        assertThat(jdbc.queryForObject("select size_bytes from artifact_blob where sha256=?",
                Integer.class, receipt.logBlobId())).isEqualTo(262144);
        assertThat(jdbc.queryForObject("select reports_blob_id from runner_invocation where invocation_id=?",
                String.class, request.invocationId())).isNotNull();
    }

    @Test void compileFailureIsAResultWithoutInfrastructureRetry() throws Exception {
        var receipt = result(request(Operation.TEST, "not valid java"));
        assertThat(receipt.status()).isEqualTo("FAILED");
        assertThat(receipt.attempt()).isEqualTo(1);
        assertThat(receipt.uncertain()).isFalse();
    }

    @Test void resourceExhaustionIsContainedInCandidate() throws Exception {
        var request = request(Operation.TEST, """
                import org.junit.jupiter.api.Test; import java.util.*; import java.io.*;
                import static org.junit.jupiter.api.Assertions.*;
                class ProbeTest { @Test void bounded() throws Exception {
                    var blocks = new ArrayList<byte[]>(); boolean memoryLimited = false;
                    try { for (int i = 0; i < 128; i++) blocks.add(new byte[8 * 1024 * 1024]); }
                    catch (OutOfMemoryError expected) { memoryLimited = true; }
                    finally { blocks.clear(); System.gc(); }
                    assertTrue(memoryLimited); System.out.println("MEMORY_LIMIT_ENFORCED");
                    var children = new ArrayList<Process>(); boolean processLimited = false;
                    try { for (int i = 0; i < 160; i++) children.add(new ProcessBuilder("/bin/sleep", "30").start()); }
                    catch (IOException | OutOfMemoryError expected) { processLimited = true; }
                    finally { for (var child : children) child.destroyForcibly(); }
                    assertTrue(processLimited); System.out.println("PID_LIMIT_ENFORCED");
                } }
                """);
        var receipt = result(request);
        assertThat(receipt.status()).isEqualTo("SUCCEEDED");
        String logs = jdbc.queryForObject("select convert_from(content,'UTF8') from artifact_blob where sha256=?",
                String.class, receipt.logBlobId());
        assertThat(logs).contains("MEMORY_LIMIT_ENFORCED", "PID_LIMIT_ENFORCED");
    }
}
