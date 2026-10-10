package com.brianmehrman.harness.runner;

import com.brianmehrman.harness.workspace.WorkspaceStore;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcRunner implements Runner {
    private final JdbcTemplate jdbc;
    private final WorkspaceStore workspaces;
    private final String image;
    private final JsonMapper json=JsonMapper.builder().build();
    public JdbcRunner(JdbcTemplate jdbc, WorkspaceStore workspaces,
                      @Value("${harness.runner.image:}") String image) {
        this.jdbc=new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        this.jdbc.setQueryTimeout(10); this.workspaces=workspaces; this.image=image;
    }
    @Override public void ensureStarted(InvocationRequest request) {
        // Existing receipts keep their original image across deployments.
        if(request==null) throw new IllegalArgumentException("Request required");
        InvocationHasher.identity(request.invocationId());
        var old=jdbc.queryForList("select image_id from runner_invocation where invocation_id=?",String.class,request.invocationId());
        String frozenImage=old.isEmpty()?image:old.getFirst();
        String hash=InvocationHasher.hash(request,frozenImage);
        workspaces.files(request.runId(),request.snapshot());
        if(old.isEmpty() && request.deadlineEpochMillis()-System.currentTimeMillis()>120_000)
            throw new IllegalArgumentException("Deadline must be within 120 seconds");
        jdbc.update("insert into runner_invocation(invocation_id,run_id,input_sha256,snapshot_sha256,operation,image_id,deadline_at,container_name) values (?,?,?,?,?,?,?,?) on conflict (invocation_id) do nothing",
            request.invocationId(),request.runId(),hash,request.snapshot().sha256(),request.operation().name(),frozenImage,
            new Timestamp(request.deadlineEpochMillis()),containerName(hash,1));
        var stored=jdbc.queryForMap("select input_sha256,image_id from runner_invocation where invocation_id=?",request.invocationId());
        if(!stored.get("input_sha256").equals(InvocationHasher.hash(request,(String)stored.get("image_id"))))
            throw new IllegalArgumentException("Invocation ID reused with different input");
    }
    @Override public Optional<InvocationResult> result(String id) {
        InvocationHasher.identity(id);
        var rows=jdbc.queryForList("select result_json::text from runner_invocation where invocation_id=? and result_json is not null",String.class,id);
        return rows.isEmpty()?Optional.empty():Optional.of(json.readValue(rows.getFirst(),InvocationResult.class));
    }
    @Override public void cancel(String id) {
        InvocationHasher.identity(id);
        jdbc.update("update runner_invocation set requested_cancel=true where invocation_id=? and result_json is null",id);
    }
    @Override public boolean stopConfirmed(String id) {
        InvocationHasher.identity(id);
        var rows=jdbc.queryForList("select result_json is not null and cleaned from runner_invocation where invocation_id=?",
            Boolean.class,id);
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.getFirst());
    }
    static String containerName(String hash,int attempt) { return "harness-"+hash+"-"+attempt; }
}
