package com.brianmehrman.harness.benchmark;

import com.brianmehrman.harness.runner.*;
import com.brianmehrman.harness.workspace.*;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Assertions, reports, and receipts stay outside candidate containers. */
@Component
public class TaskTrackerEvaluator implements Evaluator {
    private final JdbcTemplate jdbc;
    private final WorkspaceStore workspaces;
    private final Runner runner;
    private final EvaluationRuntime runtime;
    private final TaskTrackerDefinition definition;
    private final String image;
    private final JsonMapper json=JsonMapper.builder().build();
    public TaskTrackerEvaluator(JdbcTemplate jdbc,WorkspaceStore workspaces,Runner runner,EvaluationRuntime runtime,
                                TaskTrackerDefinition definition,@Value("${harness.runner.image:}") String image) {
        this.jdbc=jdbc;this.workspaces=workspaces;this.runner=runner;this.runtime=runtime;this.definition=definition;this.image=image;
    }
    @Override public Evaluation evaluate(String run,String invocation,SnapshotRef snapshot,long seed,long deadline) {
        identity(run);identity(invocation);Objects.requireNonNull(snapshot);
        var files=workspaces.files(run,snapshot);
        var existing=jdbc.queryForList("select * from evaluation where invocation_id=?",invocation);
        String frozenImage=existing.isEmpty()?image:(String)existing.getFirst().get("image_id");
        String version=existing.isEmpty()?definition.evaluatorVersion():(String)existing.getFirst().get("evaluator_version");
        String hash=SnapshotHasher.content(json.writeValueAsString(List.of("evaluation-v1",run,invocation,snapshot.sha256(),seed,deadline,frozenImage,version)));
        if(existing.isEmpty()) {
            if(deadline<=System.currentTimeMillis() || deadline-System.currentTimeMillis()>180000 || !frozenImage.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException("Pinned image and deadline within 180 seconds required");
            jdbc.update("insert into evaluation(invocation_id,run_id,snapshot_sha256,input_sha256,image_id,evaluator_version,seed,deadline_at,build_deadline_at) values (?,?,?,?,?,?,?,?,?) on conflict do nothing",
                invocation,run,snapshot.sha256(),hash,frozenImage,version,seed,new Timestamp(deadline),new Timestamp(Math.min(deadline,System.currentTimeMillis()+120000)));
        }
        var row=jdbc.queryForMap("select * from evaluation where invocation_id=?",invocation);
        if(!hash.equals(row.get("input_sha256"))) throw new IllegalArgumentException("Submission ID reused with conflicting input");
        if(row.get("result_json")!=null) return json.readValue(row.get("result_json").toString(),Evaluation.class);
        if(!definition.evaluatorVersion().equals(version) || !image.equals(frozenImage)) throw new IllegalStateException("Frozen evaluator version/image unavailable");
        // Session advisory lock serializes controllers across worker processes; no long DB transaction.
        try(Connection connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            boolean locked=false;
            try {
                while(!locked) {
                    try(var statement=connection.prepareStatement("select pg_try_advisory_lock(hashtextextended(?,0))")) {
                        statement.setString(1,"evaluation:"+invocation);try(var result=statement.executeQuery()) { result.next();locked=result.getBoolean(1); }
                    }
                    if(!locked) { var result=stored(invocation);if(result.isPresent()) return result.get();TaskTrackerCases.pause(deadline); }
                }
                var result=stored(invocation);if(result.isPresent()) return result.get();
                return execute(run,invocation,snapshot,seed,deadline,version,files);
            } finally {
                if(locked) try(var statement=connection.prepareStatement("select pg_advisory_unlock(hashtextextended(?,0))")) { statement.setString(1,"evaluation:"+invocation);statement.execute(); }
            }
        } catch(SQLException e) { throw new IllegalStateException("Evaluation receipt unavailable",e); }
    }
    private Optional<Evaluation> stored(String id) {
        var rows=jdbc.queryForList("select result_json::text from evaluation where invocation_id=? and result_json is not null",String.class,id);
        return rows.isEmpty()?Optional.empty():Optional.of(json.readValue(rows.getFirst(),Evaluation.class));
    }
    private Evaluation execute(String run,String invocation,SnapshotRef snapshot,long seed,long deadline,String version,Map<String,String> files) {
        var failed=new LinkedHashSet<String>();var diagnostics=new LinkedHashMap<String,Object>();
        String artifact=null;int count=0;boolean infrastructure=false;
        if(files.getOrDefault("README.md","").isBlank()) failed.add("readme");
        boolean source=files.entrySet().stream().anyMatch(e->e.getKey().startsWith("src/test/java/") && e.getKey().endsWith(".java") && !e.getValue().isBlank());
        try {
            long buildDeadline=jdbc.queryForObject("select build_deadline_at from evaluation where invocation_id=?",Timestamp.class,invocation).getTime();
            var build=command(run,invocation,snapshot,Operation.BUILD,buildDeadline);diagnostics.put("build",build);infrastructure=runnerInfrastructureFailure(build,buildDeadline,deadline);
            if(!build.status().equals("SUCCEEDED") || build.artifactBlobId()==null) {
                failed.addAll(TaskTrackerDefinition.CASES.subList(0,14));
                return finish(invocation,snapshot,seed,version,null,0,failed,diagnostics,runnerInfrastructureFailure(build,buildDeadline,deadline));
            }
            artifact=build.artifactBlobId();
            jdbc.update("update evaluation set artifact_sha256=?,test_deadline_at=coalesce(test_deadline_at,?) where invocation_id=?",artifact,new Timestamp(Math.min(deadline,System.currentTimeMillis()+120000)),invocation);
            long testDeadline=jdbc.queryForObject("select test_deadline_at from evaluation where invocation_id=?",Timestamp.class,invocation).getTime();
            var tests=command(run,invocation,snapshot,Operation.TEST,testDeadline);diagnostics.put("tests",tests);
            count=reportedTestCount(tests.invocationId());
            // XML is diagnostic only. Actual immutable Gradle TEST must succeed and candidate test source must exist.
            if(!source || !tests.status().equals("SUCCEEDED")) failed.add("agent_tests");
            infrastructure|=runnerInfrastructureFailure(tests,testDeadline,deadline);
            for(String caseName:TaskTrackerDefinition.CASES.subList(0,13)) {
                // Per-case seeds allow completed cases to replay without consuming a shared RNG differently.
                Random random=new Random(seed ^ caseName.hashCode());
                String session=runtime.open(invocation,caseName);
                var saved=jdbc.queryForList("select diagnostics ->> ? from evaluation where invocation_id=?",String.class,caseName,invocation);
                String status=saved.isEmpty()?null:saved.getFirst();
                if(status!=null) { infrastructure|=status.startsWith("runtime:");diagnostics.put(caseName,status);if(!status.equals("passed")) failed.add(caseName);runtime.close(session);continue; }
                try {
                    while(!runtime.ready(session)) TaskTrackerCases.pause(deadline);
                    new TaskTrackerCases(runtime,session,deadline).run(caseName,random);status="passed";
                } catch(TaskTrackerCases.Failure e) { status=e.getMessage();failed.add(caseName);infrastructure|=status.startsWith("runtime:"); }
                  catch(IllegalStateException e) { status="runtime:lost_evidence_or_unavailable";failed.add(caseName);infrastructure=true; }
                // Case marker commits before closing; replay never needs to reconstruct a closed tmpfs case.
                jdbc.update("update evaluation set diagnostics=jsonb_set(diagnostics,array[?],to_jsonb(?::text)) where invocation_id=?",caseName,status,invocation);
                diagnostics.put(caseName,status);runtime.close(session);
            }
        } catch(TaskTrackerCases.Failure | IllegalStateException e) {
            infrastructure=true;diagnostics.put("infrastructure","execution_unavailable_or_deadline");failed.addAll(TaskTrackerDefinition.CASES.subList(0,14));
        }
        return finish(invocation,snapshot,seed,version,artifact,count,failed,diagnostics,infrastructure);
    }
    private static boolean runnerInfrastructureFailure(InvocationResult result,long commandDeadline,long evaluationDeadline) {
        if(!result.uncertain() && result.status().equals("TIMED_OUT") && commandDeadline<evaluationDeadline
            && result.exitCode()!=null && Set.of(124,137,143).contains(result.exitCode())) return false;
        return result.uncertain() || (!result.status().equals("SUCCEEDED") &&
            !(result.status().equals("FAILED") && result.exitCode()!=null && result.exitCode()!=0 && result.exitCode()!=70));
    }
    private InvocationResult command(String run,String id,SnapshotRef snapshot,Operation operation,long deadline) {
        String child="eval-"+SnapshotHasher.content(id+"/"+operation.name());
        runner.ensureStarted(new InvocationRequest(run,child,snapshot,operation,deadline));
        while(true) {
            var result=runner.result(child);if(result.isPresent()) return result.get();
            if(System.currentTimeMillis()>=deadline) { runner.cancel(child);throw new IllegalStateException("Runner deadline"); }
            TaskTrackerCases.pause(deadline);
        }
    }
    private Evaluation finish(String id,SnapshotRef snapshot,long seed,String version,String artifact,int count,Set<String> failed,Map<String,Object> diagnostics,boolean infrastructure) {
        var ordered=TaskTrackerDefinition.CASES.stream().filter(failed::contains).toList();
        var outcome=infrastructure?Evaluation.Outcome.INFRASTRUCTURE_FAILED:ordered.isEmpty()?Evaluation.Outcome.PASSED:Evaluation.Outcome.REJECTED;
        var result=new Evaluation(outcome==Evaluation.Outcome.PASSED,snapshot.sha256(),artifact,version,seed,ordered,count,outcome);
        jdbc.update("update evaluation set result_json=?::jsonb,diagnostics=diagnostics || ?::jsonb where invocation_id=? and result_json is null",json.writeValueAsString(result),json.writeValueAsString(diagnostics),id);
        return stored(id).orElseThrow();
    }
    /** This count is reported/discovered diagnostic evidence, never a verdict from candidate XML. */
    private int reportedTestCount(String id) {
        var blobs=jdbc.queryForList("select b.content from runner_invocation r join artifact_blob b on b.sha256=r.reports_blob_id where r.invocation_id=?",byte[].class,id);
        if(blobs.isEmpty()) return 0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(blobs.getFirst()))) {
            var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
            int count=0,total=0,entries=0;
            while(zip.getNextEntry()!=null) {
                if(++entries>256) return 0;byte[] data=zip.readNBytes(4194304-total+1);total+=data.length;if(total>4194304) return 0;
                var document=factory.newDocumentBuilder().parse(new ByteArrayInputStream(data));
                count=Math.addExact(count,document.getElementsByTagName("testcase").getLength());
            }
            return count;
        } catch(Exception ignored) { return 0; }
    }
    private static void identity(String value) { if(value==null || value.isBlank() || value.length()>200 || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid evaluation identity"); }
}
