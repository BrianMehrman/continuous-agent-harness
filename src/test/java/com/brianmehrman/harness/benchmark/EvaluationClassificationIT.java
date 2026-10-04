package com.brianmehrman.harness.benchmark;

import com.brianmehrman.harness.runner.*;
import com.brianmehrman.harness.workspace.*;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Recovery starts from a durable runner receipt; no candidate is needed to classify lost evidence. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="harness.runner.enabled=false")
@ActiveProfiles("runner")
class EvaluationClassificationIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkspaceStore workspaces;
    @Autowired Runner runner;
    @Autowired Evaluator evaluator;
    @Autowired TaskTrackerDefinition definition;
    @Test void uncertainBuildReceiptProducesExplicitIdempotentInfrastructureOutcome() {
        String run="classification-"+UUID.randomUUID(),id=UUID.randomUUID().toString();
        var snapshot=workspaces.seed(run,"task-tracker-v1");long seed=42,deadline=System.currentTimeMillis()+180000,buildDeadline=deadline-60000;
        String image=System.getenv("HARNESS_RUNNER_IMAGE"),version=definition.evaluatorVersion();var json=JsonMapper.builder().build();
        String hash=SnapshotHasher.content(json.writeValueAsString(List.of("evaluation-v1",run,id,snapshot.sha256(),seed,deadline,image,version)));
        jdbc.update("insert into evaluation(invocation_id,run_id,snapshot_sha256,input_sha256,image_id,evaluator_version,seed,deadline_at,build_deadline_at) values (?,?,?,?,?,?,?,?,?)",
            id,run,snapshot.sha256(),hash,image,version,seed,new Timestamp(deadline),new Timestamp(buildDeadline));
        String child="eval-"+SnapshotHasher.content(id+"/BUILD");
        var request=new InvocationRequest(run,child,snapshot,Operation.BUILD,buildDeadline);runner.ensureStarted(request);
        var receipt=new InvocationResult(child,InvocationHasher.hash(request,image),"UNCERTAIN",null,null,false,null,2,true);
        jdbc.update("update runner_invocation set status='UNCERTAIN',attempt=2,uncertain=true,result_json=?::jsonb,cleaned=true where invocation_id=?",json.writeValueAsString(receipt),child);
        var first=evaluator.evaluate(run,id,snapshot,seed,deadline);
        assertThat(first.passed()).isFalse();assertThat(first.outcome()).isEqualTo(Evaluation.Outcome.INFRASTRUCTURE_FAILED);
        assertThat(first.artifactSha256()).isNull();assertThat(first.failedCases()).contains("empty","agent_tests");
        assertThat(evaluator.evaluate(run,id,snapshot,seed,deadline)).isEqualTo(first);
        assertThat(jdbc.queryForObject("select diagnostics -> 'build' ->> 'status' from evaluation where invocation_id=?",String.class,id)).isEqualTo("UNCERTAIN");
    }
}
