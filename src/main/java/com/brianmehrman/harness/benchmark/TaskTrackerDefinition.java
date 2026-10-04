package com.brianmehrman.harness.benchmark;
import com.brianmehrman.harness.workspace.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
@Component
public class TaskTrackerDefinition {
    public static final List<String> CASES=List.of("empty","add_list","complete","complete_twice","restart","isolated_dirs","unicode_spaces","duplicate_descriptions","invalid_command","invalid_args","invalid_id","invalid_description","failed_write","agent_tests","readme");
    private final BenchmarkStarter starter;
    public TaskTrackerDefinition(BenchmarkStarter starter) { this.starter=starter; }
    public String benchmarkVersion() { return "task-tracker-v1"; }
    public String starterDigest() { return SnapshotHasher.snapshot(starter.files(benchmarkVersion())); }
    public String requirements() { return starter.files(benchmarkVersion()).get("REQUIREMENTS.md"); }
    public String evaluatorVersion() {
        try(var in=new ClassPathResource("benchmarks/task-tracker-v1/evaluator-source.sha256").getInputStream()) {
            return "task-tracker-evaluator-v1:"+new String(in.readAllBytes(),StandardCharsets.US_ASCII).strip();
        } catch(IOException e) { throw new IllegalStateException("Evaluator provenance unavailable",e); }
    }
    public Map<String,Map<String,Object>> toolSchemas() {
        var result=new LinkedHashMap<String,Map<String,Object>>();
        result.put("list_files",schema(Map.of("prefix",Map.of("type","string")),List.of()));
        result.put("read_file",schema(Map.of("path",Map.of("type","string")),List.of("path")));
        result.put("write_file",schema(Map.of("path",Map.of("type","string"),"expected_sha256",Map.of("type","string"),"content",Map.of("type","string")),List.of("path","expected_sha256","content")));
        result.put("run_tests",schema(Map.of(),List.of()));result.put("build",schema(Map.of(),List.of()));
        result.put("submit",schema(Map.of("summary",Map.of("type","string")),List.of("summary")));
        return Collections.unmodifiableMap(result);
    }
    private Map<String,Object> schema(Map<String,?> properties,List<String> required) { return Map.of("type","object","properties",properties,"required",required,"additionalProperties",false); }
}
