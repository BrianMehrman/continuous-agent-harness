package com.brianmehrman.harness.runner;

import com.brianmehrman.harness.workspace.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("runner")
@ConditionalOnProperty(name="harness.runner.enabled",havingValue="true")
public class RunnerSupervisor {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final WorkspaceStore workspaces;
    private final DockerCommandClient docker=new DockerCommandClient();
    private final RunnerBoundary boundary;
    private final JsonMapper json=JsonMapper.builder().build();
    private final String owner=UUID.randomUUID().toString();
    private final ScheduledExecutorService poller=Executors.newSingleThreadScheduledExecutor();
    public RunnerSupervisor(JdbcTemplate jdbc,PlatformTransactionManager manager,WorkspaceStore workspaces,
                            ObjectProvider<RunnerBoundary> observer) {
        this.jdbc=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource())); this.jdbc.setQueryTimeout(10);
        transactions=new TransactionTemplate(manager); transactions.setTimeout(55);
        this.workspaces=workspaces; boundary=observer.getIfAvailable(() -> (point,id) -> {});
    }
    @PostConstruct void start() { poller.scheduleWithFixedDelay(this::pollSafely,0,1,TimeUnit.SECONDS); }
    @PreDestroy void shutdown() { poller.shutdownNow(); }
    private void pollSafely() {
        try { poll(); }
        catch(Exception e) { LoggerFactory.getLogger(getClass()).warn("Runner reconciliation deferred: {}",e.getClass().getSimpleName()); }
    }
    void poll() {
        String completed=transactions.execute(tx -> {
            // The row lock is the fencing mechanism, held through all external actions.
            // A lease timestamp alone cannot fence a paused process from Docker.
            jdbc.execute("set local statement_timeout='10s'");
            var rows=jdbc.queryForList("select * from runner_invocation where not cleaned order by checked_at for update skip locked limit 1");
            if(rows.isEmpty()) return null;
            Map<String,Object> row=rows.getFirst(); String id=(String)row.get("invocation_id");
            jdbc.update("update runner_invocation set lease_owner=?,lease_until=now()+interval '55 seconds',checked_at=now() where invocation_id=?",owner,id);
            try { reconcile(row); }
            catch(DockerCommandClient.Collision e) {
                if(row.get("result_json")==null) finish(row,"FAILED",null,null,"Container ownership mismatch");
                // Never touch a colliding user container, including during cleanup.
                jdbc.update("update runner_invocation set cleaned=true where invocation_id=? and lease_owner=?",id,owner);
            }
            catch(DockerCommandClient.Unavailable e) { /* Retain evidence/state and retry on the next poll. */ }
            jdbc.update("update runner_invocation set lease_owner=null,lease_until=null where invocation_id=? and lease_owner=?",id,owner);
            return row.get("result_json")==null && resultPresent(id)?id:null;
        });
        if(completed!=null) boundary.reached("AFTER_RESULT_COMMIT",completed);
    }
    private boolean resultPresent(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select result_json is not null from runner_invocation where invocation_id=?",Boolean.class,id));
    }
    private void reconcile(Map<String,Object> row) {
        String id=(String)row.get("invocation_id"), name=(String)row.get("container_name"), hash=(String)row.get("input_sha256");
        if(row.get("result_json")==null && row.get("status").equals("REQUESTED")) boundary.reached("AFTER_LEDGER_COMMIT",id);
        Optional<DockerCommandClient.Container> existing=docker.inspect(name,hash);
        if(row.get("result_json")!=null) {
            if(existing.isPresent()) {
                if(existing.get().state().equals("running")) { docker.stop(existing.get()); return; }
                docker.remove(existing.get());
            }
            jdbc.update("update runner_invocation set cleaned=true where invocation_id=? and lease_owner=?",id,owner); return;
        }
        boolean cancelled=(Boolean)row.get("requested_cancel");
        boolean expired=System.currentTimeMillis()>=((Timestamp)row.get("deadline_at")).getTime();
        boolean completedInTime=existing.isPresent() && existing.get().state().equals("exited")
                && existing.get().finishedAt()<=((Timestamp)row.get("deadline_at")).getTime();
        if(cancelled || (expired && !completedInTime)) {
            if(existing.isPresent() && existing.get().state().equals("running")) {
                docker.stop(existing.get()); existing=docker.inspect(name,hash);
                if(existing.isPresent() && existing.get().state().equals("running")) return;
            }
            finish(row,cancelled?"CANCELLED":"TIMED_OUT",existing.map(DockerCommandClient.Container::exitCode).orElse(null),null,"Execution stopped"); return;
        }
        if(existing.isEmpty()) {
            if(!row.get("status").equals("REQUESTED")) { retryOrFinish(row,"Container evidence disappeared"); return; }
            docker.create(name,hash,(String)row.get("image_id"),Operation.valueOf((String)row.get("operation")),((Timestamp)row.get("deadline_at")).getTime());
            boundary.reached("AFTER_CREATE",id);
            jdbc.update("update runner_invocation set status='STAGING' where invocation_id=? and lease_owner=?",id,owner); return;
        }
        var container=existing.get();
        if(container.state().equals("created")) { docker.start(container); return; }
        if(container.state().equals("running")) {
            if(!row.get("status").equals("RUNNING")) {
                docker.stage(container,workspaces.files((String)row.get("run_id"),new SnapshotRef((String)row.get("snapshot_sha256"))));
                boundary.reached("AFTER_START",id);
                jdbc.update("update runner_invocation set status='RUNNING' where invocation_id=? and lease_owner=?",id,owner);
            }
            return;
        }
        if(!container.state().equals("exited")) { throw new DockerCommandClient.Unavailable("Container transition pending"); }
        boundary.reached("AFTER_EXIT",id);
        DockerCommandClient.Output output;
        try { output=docker.output(container); }
        catch(IllegalArgumentException e) {
            finish(row,"FAILED",container.exitCode(),null,"Invalid or missing runner output"); return;
        }
        String status=container.exitCode()==0?"SUCCEEDED":container.exitCode()==124?"TIMED_OUT":"FAILED";
        if(row.get("operation").equals("BUILD") && (output.artifact().length==0 || !output.artifactError().isEmpty())) status="FAILED";
        finish(row,status,container.exitCode(),output,null);
    }
    private void retryOrFinish(Map<String,Object> row,String message) {
        String id=(String)row.get("invocation_id"); int attempt=(Integer)row.get("attempt");
        if(attempt==1) {
            jdbc.update("update runner_invocation set uncertain=true,attempt=2,status='REQUESTED',container_name=? where invocation_id=? and lease_owner=?",
                JdbcRunner.containerName((String)row.get("input_sha256"),2),id,owner);
        } else {
            row.put("uncertain",true); finish(row,"UNCERTAIN",null,null,message);
        }
    }
    private void finish(Map<String,Object> row,String status,Integer exit,DockerCommandClient.Output output,String message) {
        String id=(String)row.get("invocation_id");
        byte[] logs=output==null?message.getBytes(StandardCharsets.UTF_8):output.logs();
        String log=blob(logs,"text/plain"),artifact=output==null || output.artifact().length==0?null:blob(output.artifact(),"application/java-archive");
        String reports=output==null || output.reports().length==0?null:blob(output.reports(),"application/zip");
        var result=new InvocationResult(id,(String)row.get("input_sha256"),status,exit,log,output!=null && output.truncated(),artifact,(Integer)row.get("attempt"),(Boolean)row.get("uncertain"));
        jdbc.update("update runner_invocation set status=?,result_json=?::jsonb,reports_blob_id=? where invocation_id=? and lease_owner=? and result_json is null",
            status,json.writeValueAsString(result),reports,id,owner);
    }
    private String blob(byte[] bytes,String type) {
        if(bytes.length>DockerCommandClient.ARTIFACT_LIMIT) throw new IllegalArgumentException("Blob too large");
        String hash=InvocationHasher.digest(bytes);
        jdbc.update("insert into artifact_blob(sha256,media_type,content,size_bytes) values (?,?,?,?) on conflict do nothing",hash,type,bytes,bytes.length); return hash;
    }
}
