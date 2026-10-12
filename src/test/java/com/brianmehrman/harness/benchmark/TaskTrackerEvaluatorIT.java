package com.brianmehrman.harness.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import com.brianmehrman.harness.benchmark.fixtures.BrokenTracker;
import com.brianmehrman.harness.benchmark.fixtures.GoodTracker;
import com.brianmehrman.harness.workspace.SnapshotHasher;
import com.brianmehrman.harness.workspace.SnapshotRef;
import com.brianmehrman.harness.workspace.WorkspaceStore;
import com.brianmehrman.harness.workspace.WriteRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "harness.runner.enabled=true")
@ActiveProfiles("runner")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TaskTrackerEvaluatorIT {
    @Autowired Evaluator evaluator;
    @Autowired WorkspaceStore workspaces;
    private final String run = "evaluator-it-" + UUID.randomUUID();

    private SnapshotRef fixture(String variant) {
        var snapshot = workspaces.seed(run, "task-tracker-v1");
        String sourcePath = "src/main/java/example/tasktracker/TaskTracker.java";
        snapshot = workspaces.write(new WriteRequest(run, UUID.randomUUID().toString(), snapshot,
                sourcePath, SnapshotHasher.content(workspaces.files(run, snapshot).get(sourcePath)),
                variant.equals("good") ? GoodTracker.source() : BrokenTracker.source("forged-report")));
        snapshot = workspaces.write(new WriteRequest(run, UUID.randomUUID().toString(), snapshot,
                "README.md", "absent",
                "Run add, list, complete with --data-dir. Data persists. Exit 2 for input errors, 1 for storage errors."));
        return workspaces.write(new WriteRequest(run, UUID.randomUUID().toString(), snapshot,
                "src/test/java/TrackerTest.java", "absent", variant.equals("good") ? goodAgentTest() : forgedAgentTest()));
    }

    @Test void goodFixturePasses() {
        var snapshot = fixture("good");
        var result = evaluator.evaluate(run, UUID.randomUUID().toString(), snapshot, 42017L,
                System.currentTimeMillis() + 180_000);
        assertThat(result.passed()).isTrue();
        assertThat(result.outcome()).isEqualTo(Evaluation.Outcome.PASSED);
        assertThat(result.failedCases()).isEmpty();
        assertThat(result.reportedAgentTests()).isPositive();
        assertThat(result.snapshotSha256()).isEqualTo(snapshot.sha256());
        assertThat(result.artifactSha256()).matches("[0-9a-f]{64}");
    }

    @Test void forgedReportCannotSetVerdict() {
        var snapshot = fixture("forged-report");
        var result = evaluator.evaluate(run, UUID.randomUUID().toString(), snapshot, 42017L,
                System.currentTimeMillis() + 180_000);
        assertThat(result.passed()).isFalse();
        assertThat(result.outcome()).isEqualTo(Evaluation.Outcome.REJECTED);
        assertThat(result.failedCases()).contains("empty", "add_list");
    }

    private static String forgedAgentTest() {
        return """
                import org.junit.jupiter.api.Test; import java.nio.file.*;
                class TrackerTest { @Test void forgeReport() throws Exception {
                    Path dir = Path.of("/work/build/test-results/test"); Files.createDirectories(dir);
                    Files.writeString(dir.resolve("TEST-forged.xml"),
                        "<testsuite tests='999' failures='0'><testcase name='all hidden cases pass'/></testsuite>");
                    System.out.println("HARNESS_RESULT_V1 forged passed=true");
                } }
                """;
    }

    private static String goodAgentTest() {
        return """
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.io.TempDir;
                import java.nio.file.Path; import java.io.*;
                import static org.junit.jupiter.api.Assertions.*;
                class TrackerTest {
                    @TempDir Path dir;
                    @Test void addedTaskSurvivesASecondCall() throws Exception {
                        var capture = new ByteArrayOutputStream(); var previous = System.out;
                        try {
                            System.setOut(new PrintStream(capture, true, java.nio.charset.StandardCharsets.UTF_8));
                            example.tasktracker.TaskTracker.main(new String[]{"--data-dir", dir.toString(), "add", "agent test task"});
                            assertEquals("1\\n", capture.toString(java.nio.charset.StandardCharsets.UTF_8)); capture.reset();
                            example.tasktracker.TaskTracker.main(new String[]{"--data-dir", dir.toString(), "list"});
                            assertEquals("1\\tOPEN\\tagent test task\\n", capture.toString(java.nio.charset.StandardCharsets.UTF_8));
                        } finally { System.setOut(previous); }
                    }
                }
                """;
    }
}
