package com.brianmehrman.harness.runner;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Durable fresh-process runtime; independently reconciles while the evaluator/worker is down. */
@Component @Profile("runner") @ConditionalOnProperty(name="harness.runner.enabled",havingValue="true")
public class EvaluationRuntimeSupervisor {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EvaluationDocker docker=new EvaluationDocker();
    private final JsonMapper json=JsonMapper.builder().build();
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor();
    public EvaluationRuntimeSupervisor(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));this.jdbc.setQueryTimeout(10);
        tx=new TransactionTemplate(manager);tx.setTimeout(55);
    }
    @PostConstruct void start() { executor.scheduleWithFixedDelay(this::pollSafely,0,50,TimeUnit.MILLISECONDS); }
    @PreDestroy void stop() { executor.shutdownNow(); }
    private void pollSafely() {
        try { tx.executeWithoutResult(s -> poll()); }
        catch(Exception e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Evaluation runtime reconciliation deferred: {}",e.getClass().getSimpleName()); }
    }
    void poll() {
        jdbc.execute("set local statement_timeout='10s'");
        var rows=jdbc.queryForList("select * from evaluation_runtime where state <> 'CLOSED' order by checked_at for update skip locked limit 1");
        if(rows.isEmpty()) return;
        var row=rows.getFirst(); String id=(String)row.get("session_id");
        jdbc.update("update evaluation_runtime set checked_at=now() where session_id=?",id);
        try { reconcile(row); }
        catch(DockerCommandClient.Collision e) {
            // Foreign objects must never be killed/removed. Surface a visible terminal fault.
            jdbc.update("update evaluation_runtime set state='FAILED',failure='ownership_collision' where session_id=?",id);
            jdbc.update("update evaluation_command set state='COMPLETE',result_json=?::jsonb where session_id=? and result_json is null",
                json.writeValueAsString(new EvaluationRuntime.Result(70,"","",false,"OWNERSHIP_COLLISION")),id);
        }
        catch(DockerCommandClient.Unavailable e) { /* Keep durable evidence and retry; never confirm an unobserved stop. */ }
    }
    private void reconcile(Map<String,Object> row) {
        String id=(String)row.get("session_id"),state=(String)row.get("state");
        long deadline=((Timestamp)row.get("deadline_at")).getTime();
        if(state.equals("CLOSING") || state.equals("FAILED") || System.currentTimeMillis()>=deadline) { cleanup(row); return; }
        var holder=docker.inspect(docker.holder(id),id);
        if(state.equals("REQUESTED")) {
            docker.ensureVolume(id,true);docker.ensureVolume(id,false);
            if(holder.isEmpty()) { docker.createHolder(id,(String)row.get("image_id"),deadline);holder=docker.inspect(docker.holder(id),id); }
            var c=holder.orElseThrow();
            if(c.state().equals("created")) { docker.start(c); c=docker.inspect(docker.holder(id),id).orElseThrow(); }
            if(!c.state().equals("running")) { fail(id,"holder_lost");return; }
            byte[] jar=jdbc.queryForObject("select content from artifact_blob where sha256=?",byte[].class,row.get("artifact_sha256"));
            if(!InvocationHasher.digest(jar).equals(row.get("artifact_sha256"))) { fail(id,"artifact_digest_mismatch");return; }
            docker.stage(c,jar);
            jdbc.update("update evaluation_runtime set state='READY' where session_id=?",id);return;
        }
        if(holder.isEmpty() || !holder.get().state().equals("running")) { fail(id,"holder_lost");return; }
        var commands=jdbc.queryForList("select * from evaluation_command where session_id=? and not cleaned order by ordinal limit 1",id);
        if(commands.isEmpty()) return;
        var command=commands.getFirst(); String cid=(String)command.get("command_id");
        var candidate=docker.inspect(docker.candidate(cid),cid);
        if(command.get("result_json")!=null) {
            if(candidate.isPresent()) { if(candidate.get().state().equals("running")) { docker.kill(candidate.get());return; } docker.remove(candidate.get()); }
            jdbc.update("update evaluation_command set cleaned=true where command_id=?",cid);return;
        }
        long end=((Timestamp)command.get("deadline_at")).getTime();
        boolean finished=candidate.isPresent() && candidate.get().state().equals("exited") && candidate.get().finishedAt()<=end;
        if(System.currentTimeMillis()>=end && !finished) {
            if(candidate.isPresent() && candidate.get().state().equals("running")) { docker.kill(candidate.get());return; }
            finish(cid,new EvaluationRuntime.Result(124,"","",false,"TIMED_OUT"));return;
        }
        if(candidate.isEmpty()) {
            if(command.get("state").equals("RUNNING")) { finish(cid,new EvaluationRuntime.Result(70,"","",false,"UNCERTAIN"));fail(id,"command_evidence_lost");return; }
            List<String> args=new ArrayList<>();json.readTree(command.get("arguments").toString()).forEach(n->args.add(n.asText()));
            docker.createCandidate(id,cid,(String)row.get("image_id"),args);
            candidate=docker.inspect(docker.candidate(cid),cid);
        }
        var c=candidate.orElseThrow();
        if(c.state().equals("created")) {
            // Commit start intent BEFORE the next poll can start a mutating process.
            // A missing container after this commit is uncertain, never blindly recreated.
            if(command.get("state").equals("REQUESTED")) jdbc.update("update evaluation_command set state='RUNNING' where command_id=?",cid);
            else docker.start(c);
            return;
        }
        if(c.state().equals("exited")) finish(cid,docker.output(c));
        else if(c.state().equals("running")) jdbc.update("update evaluation_command set state='RUNNING' where command_id=?",cid);
    }
    private void fail(String id,String reason) { jdbc.update("update evaluation_runtime set state='FAILED',failure=? where session_id=?",reason,id); }
    private void finish(String id,EvaluationRuntime.Result result) { jdbc.update("update evaluation_command set state='COMPLETE',result_json=?::jsonb where command_id=? and result_json is null",json.writeValueAsString(result),id); }
    private void cleanup(Map<String,Object> row) {
        String id=(String)row.get("session_id");
        for(var command:jdbc.queryForList("select * from evaluation_command where session_id=? and not cleaned",id)) {
            String cid=(String)command.get("command_id");
            Optional<EvaluationDocker.Container> c;
            try { c=docker.inspect(docker.candidate(cid),cid); }
            catch(DockerCommandClient.Collision foreign) {
                // Skip only the foreign object; still reclaim all owned case resources.
                if(command.get("result_json")==null) finish(cid,new EvaluationRuntime.Result(70,"","",false,"OWNERSHIP_COLLISION"));
                jdbc.update("update evaluation_command set cleaned=true where command_id=?",cid);continue;
            }
            if(c.isPresent()) { if(c.get().state().equals("running")) { docker.kill(c.get());return; } docker.remove(c.get()); }
            if(command.get("result_json")==null) finish(cid,new EvaluationRuntime.Result(124,"","",false,"STOPPED"));
            jdbc.update("update evaluation_command set cleaned=true where command_id=?",cid);
        }
        Optional<EvaluationDocker.Container> holder;
        try { holder=docker.inspect(docker.holder(id),id); }
        catch(DockerCommandClient.Collision foreign) { holder=Optional.empty(); }
        if(holder.isPresent()) { if(holder.get().state().equals("running")) { docker.kill(holder.get());return; } docker.remove(holder.get()); }
        for(boolean artifact:new boolean[]{true,false}) {
            try { docker.removeVolume(id,artifact); }
            catch(DockerCommandClient.Collision foreign) { /* Never delete a colliding volume. */ }
        }
        jdbc.update("update evaluation_runtime set state='CLOSED' where session_id=?",id);
    }
}
