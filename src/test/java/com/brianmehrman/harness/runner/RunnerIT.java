package com.brianmehrman.harness.runner;
import com.brianmehrman.harness.workspace.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE, properties="harness.runner.image=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
@ActiveProfiles("runner")
class RunnerIT {
 @Autowired Runner runner;
 @Autowired WorkspaceStore workspaces;
 @Autowired JdbcTemplate jdbc;
 @Test void admissionIsDurableIdempotentAndBoundToAllInputs() {
  String run="runner-it-"+UUID.randomUUID(), id=UUID.randomUUID().toString();
  var snapshot=workspaces.seed(run,"task-tracker-v1");
  var request=new InvocationRequest(run,id,snapshot,Operation.BUILD,System.currentTimeMillis()+120000);
  runner.ensureStarted(request); runner.ensureStarted(request);
  assertThat(jdbc.queryForObject("select count(*) from runner_invocation where invocation_id=?",Integer.class,id)).isEqualTo(1);
  assertThat(runner.result(id)).isEmpty();
  assertThatIllegalArgumentException().isThrownBy(() -> runner.ensureStarted(new InvocationRequest(run,id,snapshot,Operation.TEST,request.deadlineEpochMillis())));
  var changed=workspaces.write(new WriteRequest(run,"changed",snapshot,"README.md","absent","different snapshot"));
  assertThatIllegalArgumentException().isThrownBy(() -> runner.ensureStarted(new InvocationRequest(run,id,changed,Operation.BUILD,request.deadlineEpochMillis())));
  runner.cancel(id);
  assertThat(jdbc.queryForObject("select requested_cancel from runner_invocation where invocation_id=?",Boolean.class,id)).isTrue();
 }
 @Test void rejectsUnownedSnapshotsAndExcessiveDeadlines() {
  String run="runner-it-"+UUID.randomUUID(); var snapshot=workspaces.seed(run,"task-tracker-v1");
  assertThatIllegalArgumentException().isThrownBy(() -> runner.ensureStarted(new InvocationRequest("other",UUID.randomUUID().toString(),snapshot,Operation.BUILD,System.currentTimeMillis()+120000)));
  assertThatIllegalArgumentException().isThrownBy(() -> runner.ensureStarted(new InvocationRequest(run,UUID.randomUUID().toString(),snapshot,Operation.BUILD,System.currentTimeMillis()+121000)));
 }
}
