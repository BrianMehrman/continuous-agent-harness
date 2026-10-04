package com.brianmehrman.harness.runner;

import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Admission/query API; all Docker side effects belong to the independent runner supervisor. */
@Component
public class EvaluationRuntime {
    public record Result(int exitCode,String stdout,String stderr,boolean truncated,String status) {}
    private final JdbcTemplate jdbc;
    private final JsonMapper json=JsonMapper.builder().build();
    public EvaluationRuntime(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public String open(String invocation,String caseName) {
        String id=InvocationHasher.digest((invocation+"\0"+caseName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("insert into evaluation_runtime(session_id,invocation_id,case_name,image_id,artifact_sha256,deadline_at) select ?,invocation_id,?,image_id,artifact_sha256,deadline_at from evaluation where invocation_id=? and artifact_sha256 is not null on conflict do nothing",id,caseName,invocation);
        return id;
    }
    public boolean ready(String id) {
        String state=jdbc.queryForObject("select state from evaluation_runtime where session_id=?",String.class,id);
        if(Set.of("CLOSED","CLOSING","FAILED").contains(state)) throw new IllegalStateException("Runtime unavailable");
        return state.equals("READY");
    }
    public String submit(String session,int ordinal,List<String> arguments,long deadline) {
        String id=InvocationHasher.digest((session+"/"+ordinal).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String args=json.writeValueAsString(arguments);
        jdbc.update("insert into evaluation_command(command_id,session_id,ordinal,arguments,deadline_at) values (?,?,?,?::jsonb,?) on conflict do nothing",id,session,ordinal,args,new Timestamp(Math.min(deadline,System.currentTimeMillis()+5000)));
        String old=jdbc.queryForObject("select arguments::text from evaluation_command where command_id=?",String.class,id);
        if(!json.readTree(old).equals(json.readTree(args))) throw new IllegalArgumentException("Conflicting runtime command");
        return id;
    }
    public Optional<Result> result(String id) {
        var rows=jdbc.queryForList("select result_json::text from evaluation_command where command_id=? and result_json is not null",String.class,id);
        return rows.isEmpty()?Optional.empty():Optional.of(json.readValue(rows.getFirst(),Result.class));
    }
    public void close(String id) { jdbc.update("update evaluation_runtime set state='CLOSING' where session_id=? and state not in ('CLOSED','FAILED')",id); }
}
