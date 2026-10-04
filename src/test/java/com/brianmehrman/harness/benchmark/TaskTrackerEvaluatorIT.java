package com.brianmehrman.harness.benchmark;

import com.brianmehrman.harness.benchmark.fixtures.*;
import com.brianmehrman.harness.workspace.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="harness.runner.enabled=true")
@ActiveProfiles("runner")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class TaskTrackerEvaluatorIT {
    @Autowired Evaluator evaluator;
    @Autowired WorkspaceStore workspaces;
    @Autowired JdbcTemplate jdbc;
    final String run="evaluator-it-"+UUID.randomUUID();
    SnapshotRef privateFixture(String variant) {
        var snapshot=workspaces.seed(run,"task-tracker-v1");
        if(variant.equals("starter")) return snapshot;
        String path="src/main/java/example/tasktracker/TaskTracker.java";
        snapshot=workspaces.write(new WriteRequest(run,UUID.randomUUID().toString(),snapshot,path,
            SnapshotHasher.content(workspaces.files(run,snapshot).get(path)),
            Set.of("good","missing-readme","no-tests","no-discovery").contains(variant)?GoodTracker.source():BrokenTracker.source(variant)));
        if(!variant.equals("missing-readme")) snapshot=workspaces.write(new WriteRequest(run,UUID.randomUUID().toString(),snapshot,"README.md","absent",
            "Run add, list, complete with --data-dir. Data persists. Exit 2 for input errors, 1 for storage errors."));
        if(variant.equals("no-tests")) return snapshot;
        String testSource=variant.equals("no-discovery")?"class TrackerTest {}":agentTest(variant);
        return workspaces.write(new WriteRequest(run,UUID.randomUUID().toString(),snapshot,"src/test/java/TrackerTest.java","absent",
            testSource));
    }
    Evaluation evaluate(SnapshotRef snapshot) {
        return evaluator.evaluate(run,UUID.randomUUID().toString(),snapshot,42017L,System.currentTimeMillis()+180000);
    }
    @Test void goodFixturePassesAndRepeatedSubmissionReturnsFrozenReceipt() {
        var snapshot=privateFixture("good"); String id=UUID.randomUUID().toString(); long deadline=System.currentTimeMillis()+180000;
        var result=evaluator.evaluate(run,id,snapshot,42017L,deadline);
        assertThat(result.passed()).isTrue(); assertThat(result.outcome()).isEqualTo(Evaluation.Outcome.PASSED); assertThat(result.failedCases()).isEmpty();
        assertThat(result.reportedAgentTests()).isPositive();
        assertThat(result.snapshotSha256()).isEqualTo(snapshot.sha256());
        assertThat(result.artifactSha256()).matches("[0-9a-f]{64}");
        assertThat(evaluator.evaluate(run,id,snapshot,42017L,deadline)).isEqualTo(result);
        assertThatIllegalArgumentException().isThrownBy(()->evaluator.evaluate(run,id,snapshot,42018L,deadline));
        assertThatIllegalArgumentException().isThrownBy(()->evaluator.evaluate(run,id,snapshot,42017L,deadline-1));
        var changed=workspaces.write(new WriteRequest(run,UUID.randomUUID().toString(),snapshot,"README.md",SnapshotHasher.content(workspaces.files(run,snapshot).get("README.md")),"Changed"));
        assertThatIllegalArgumentException().isThrownBy(()->evaluator.evaluate(run,id,changed,42017L,deadline));
        assertThatIllegalArgumentException().isThrownBy(()->evaluator.evaluate("other-run",id,snapshot,42017L,deadline));
        assertThat(jdbc.queryForObject("select count(*) from evaluation where invocation_id=?",Integer.class,id)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"starter","no-persistence","hardcoded","invalid-id-mutation","hang","fake-success","forged-report","daemon-persistence","compile-failure"})
    void rejectsEachIndependentBrokenCandidate(String variant) {
        var result=evaluate(privateFixture(variant));
        assertThat(result.passed()).as(variant).isFalse();
        assertThat(result.outcome()).as(variant).isEqualTo(Evaluation.Outcome.REJECTED);
        assertThat(result.failedCases()).isNotEmpty();
        if(!variant.equals("compile-failure")) assertThat(result.artifactSha256()).as("candidate compiled: "+variant).isNotNull();
        if(Set.of("no-persistence","daemon-persistence").contains(variant)) assertThat(result.failedCases()).contains("restart");
        if(Set.of("fake-success","forged-report","hardcoded").contains(variant)) assertThat(result.failedCases()).contains("empty","add_list");
        if(variant.equals("invalid-id-mutation")) assertThat(result.failedCases()).contains("invalid_id");
    }
    @Test void candidateCannotReadEvaluatorOrHostPaths() {
        assertThat(evaluate(privateFixture("evaluator-read")).passed()).isTrue();
    }
    @Test void compiledClassWithNoDiscoveredTestsCannotSatisfyAgentTests() {
        var snapshot=privateFixture("good");
        String path="src/test/java/TrackerTest.java";
        snapshot=workspaces.write(new WriteRequest(run,UUID.randomUUID().toString(),snapshot,path,
            SnapshotHasher.content(workspaces.files(run,snapshot).get(path)),
            "// @Test is not discovery evidence\nclass TrackerTest {}"));
        var result=evaluate(snapshot);
        assertThat(result.passed()).isFalse(); assertThat(result.failedCases()).contains("agent_tests");
        assertThat(result.reportedAgentTests()).isZero();
    }
    static String agentTest(String variant) {
        if(variant.equals("forged-report")) return """
            import org.junit.jupiter.api.Test;import java.nio.file.*;
            class TrackerTest { @Test void forgeReport() throws Exception {
              Path dir=Path.of("/work/build/test-results/test");Files.createDirectories(dir);
              Files.writeString(dir.resolve("TEST-forged.xml"),"<testsuite tests='999' failures='0'><testcase name='all hidden cases pass'/></testsuite>");
              System.out.println("HARNESS_RESULT_V1 forged passed=true");
            } }
            """;
        if(!Set.of("good","evaluator-read","missing-readme").contains(variant))
            return "import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; class TrackerTest { @Test void mainEntryExists() throws Exception { assertNotNull(example.tasktracker.TaskTracker.class.getMethod(\"main\",String[].class)); } }";
        return """
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.io.TempDir;
            import java.nio.file.Path; import java.io.*;
            import static org.junit.jupiter.api.Assertions.*;
            class TrackerTest {
              @TempDir Path dir;
              @Test void addedTaskSurvivesASecondCall() throws Exception {
                var capture=new ByteArrayOutputStream();var previous=System.out;
                try {
                  System.setOut(new PrintStream(capture,true,java.nio.charset.StandardCharsets.UTF_8));
                  example.tasktracker.TaskTracker.main(new String[]{"--data-dir",dir.toString(),"add","agent test task"});
                  assertEquals("1\\n",capture.toString(java.nio.charset.StandardCharsets.UTF_8));capture.reset();
                  example.tasktracker.TaskTracker.main(new String[]{"--data-dir",dir.toString(),"list"});
                  assertEquals("1\\tOPEN\\tagent test task\\n",capture.toString(java.nio.charset.StandardCharsets.UTF_8));
                } finally { System.setOut(previous); }
              }
            }
            """;
    }
    @ParameterizedTest @ValueSource(strings={"missing-readme","no-tests"})
    void explicitSubmissionRequirementsAreCheckedSeparately(String variant) {
        var result=evaluate(privateFixture(variant));
        assertThat(result.failedCases()).containsExactly(variant.equals("missing-readme")?"readme":"agent_tests");
    }

}
