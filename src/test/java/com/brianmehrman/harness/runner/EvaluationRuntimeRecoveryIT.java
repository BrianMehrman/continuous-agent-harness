package com.brianmehrman.harness.runner;

import com.brianmehrman.harness.workspace.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="harness.runner.enabled=false")
@ActiveProfiles("runner")
class EvaluationRuntimeRecoveryIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkspaceStore workspaces;
    @Autowired EvaluationRuntime runtime;
    @Autowired PlatformTransactionManager manager;
    final EvaluationDocker docker=new EvaluationDocker();
    EvaluationRuntimeSupervisor supervisor;
    String session;
    @BeforeEach void setup() { supervisor=new EvaluationRuntimeSupervisor(jdbc,manager); }
    @AfterEach void cleanup() {
        if(session!=null) { runtime.close(session);until(()->state().equals("CLOSED")); }
        supervisor.stop();
    }
    String state() { return jdbc.queryForObject("select state from evaluation_runtime where session_id=?",String.class,session); }
    void until(BooleanSupplier condition) {
        long end=System.currentTimeMillis()+20000;
        while(!condition.getAsBoolean()) { assertThat(System.currentTimeMillis()).isLessThan(end);supervisor.poll(); }
    }
    void open() {
        String run="runtime-recovery-"+UUID.randomUUID(),invocation=UUID.randomUUID().toString();
        var snapshot=workspaces.seed(run,"task-tracker-v1");byte[] bytes={1,2,3};String artifact=InvocationHasher.digest(bytes);
        jdbc.update("insert into artifact_blob(sha256,media_type,content,size_bytes) values (?,?,?,?) on conflict do nothing",artifact,"application/java-archive",bytes,bytes.length);
        long end=System.currentTimeMillis()+180000;
        jdbc.update("insert into evaluation(invocation_id,run_id,snapshot_sha256,input_sha256,image_id,evaluator_version,seed,deadline_at,build_deadline_at,artifact_sha256) values (?,?,?,?,?,?,?,?,?,?)",
            invocation,run,snapshot.sha256(),InvocationHasher.digest(invocation.getBytes(java.nio.charset.StandardCharsets.UTF_8)),System.getenv("HARNESS_RUNNER_IMAGE"),"runtime-test",1,new Timestamp(end),new Timestamp(end-60000),artifact);
        session=runtime.open(invocation,"empty");until(()->state().equals("READY"));
    }
    @Test void missingContainerAfterDurableStartIntentIsUncertainAndNeverRecreated() {
        open();String id=runtime.submit(session,0,List.of("--data-dir","/data","list"),System.currentTimeMillis()+180000);
        until(()->"RUNNING".equals(jdbc.queryForObject("select state from evaluation_command where command_id=?",String.class,id)));
        var created=docker.inspect(docker.candidate(id),id).orElseThrow();
        assertThat(created.state()).isEqualTo("created");
        docker.remove(created); // Evidence lost after the committed start intent; do not replay a possible mutation.
        until(()->runtime.result(id).isPresent());
        assertThat(runtime.result(id).orElseThrow().status()).isEqualTo("UNCERTAIN");
        assertThat(docker.inspect(docker.candidate(id),id)).isEmpty();
    }
    @Test void foreignCandidateCollisionPreservesForeignContainerButCleansOwnedVolumes() {
        open();String id=runtime.submit(session,0,List.of("--data-dir","/data","list"),System.currentTimeMillis()+180000);
        var cli=new DockerCommandClient();
        var created=cli.command(List.of("create","--name",docker.candidate(id),"--label","harness.owner=foreign-test","--entrypoint","/bin/sleep",System.getenv("HARNESS_RUNNER_IMAGE"),"30"),null,65536);
        assertThat(created.code()).isZero();
        String foreign=new String(created.out(),java.nio.charset.StandardCharsets.US_ASCII).strip();
        try {
            until(()->state().equals("CLOSED"));
            assertThat(runtime.result(id).orElseThrow().status()).isEqualTo("OWNERSHIP_COLLISION");
            assertThat(docker.inspect(docker.holder(session),session)).isEmpty();
            assertThat(cli.command(List.of("volume","inspect",docker.volume(session,true)),null,65536).code()).isNotZero();
            assertThat(cli.command(List.of("volume","inspect",docker.volume(session,false)),null,65536).code()).isNotZero();
            assertThat(cli.command(List.of("inspect",foreign),null,65536).code()).isZero();
        } finally { assertThat(cli.command(List.of("rm",foreign),null,65536).code()).isZero(); }
    }
}
