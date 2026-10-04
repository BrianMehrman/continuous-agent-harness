package com.brianmehrman.harness.benchmark;

import com.brianmehrman.harness.runner.EvaluationRuntime;
import java.util.*;

/** Only this host-side code knows assertions and seeded expected values. */
final class TaskTrackerCases {
    static final class Failure extends RuntimeException { Failure(String reason) { super(reason); } }
    private final EvaluationRuntime runtime;
    private final String session;
    private final long deadline;
    private int ordinal;
    TaskTrackerCases(EvaluationRuntime runtime,String session,long deadline) { this.runtime=runtime;this.session=session;this.deadline=deadline; }
    EvaluationRuntime.Result raw(String...args) {
        String id=runtime.submit(session,ordinal++,List.of(args),deadline);
        while(true) {
            var result=runtime.result(id);
            if(result.isPresent()) {
                var r=result.get();
                if(r.truncated() || r.status().equals("OUTPUT_LIMIT")) throw new Failure("candidate:output_limit");
                if(r.status().equals("TIMED_OUT")) throw new Failure("candidate:process_timeout");
                if(!r.status().equals("COMPLETE")) throw new Failure("runtime:"+r.status());return r;
            }
            pause(deadline);
        }
    }
    void ok(String expected,String...args) { var r=raw(args);if(r.exitCode()!=0 || !r.stdout().equals(expected)) throw new Failure("candidate:unexpected_stdout_or_exit"); }
    void invalid(String...args) { var r=raw(args);if(r.exitCode()!=2 || !r.stdout().isEmpty() || r.stderr().isBlank()) throw new Failure("candidate:invalid_input_contract"); }
    void add(String dir,String description,int id) { ok(id+"\n","--data-dir",dir,"add",description); }
    void list(String dir,String expected) { ok(expected,"--data-dir",dir,"list"); }
    void run(String name,Random random) {
        String dir="/data/tasks", description="Task "+Long.toUnsignedString(random.nextLong(),36)+" 'quoted' ; $(literal)";
        String row="1\tOPEN\t"+description+"\n";
        switch(name) {
            case "empty" -> list(dir,"");
            case "add_list" -> {
                int n=2+random.nextInt(3);StringBuilder expected=new StringBuilder();
                for(int i=1;i<=n;i++) { String d=description+" "+random.nextInt(100000);add(dir,d,i);expected.append(i).append("\tOPEN\t").append(d).append('\n'); }
                list(dir,expected.toString());
            }
            case "complete","complete_twice" -> {
                add(dir,description,1);ok("1\tDONE\n","--data-dir",dir,"complete","1");
                if(name.equals("complete_twice")) ok("1\tDONE\n","--data-dir",dir,"complete","1");
                list(dir,"1\tDONE\t"+description+"\n");add(dir,description+" next",2);
            }
            case "restart" -> { add(dir,description,1);list(dir,row);ok("1\tDONE\n","--data-dir",dir,"complete","1");list(dir,"1\tDONE\t"+description+"\n"); }
            case "isolated_dirs" -> { add(dir,description,1);list("/data/other","");add("/data/other","other "+description,1);list(dir,row);list("/data/other","1\tOPEN\tother "+description+"\n"); }
            case "unicode_spaces" -> { String d="café 日本語 🧭  "+description;add(dir,"  "+d+"  ",1);list(dir,"1\tOPEN\t"+d+"\n"); }
            case "duplicate_descriptions" -> { add(dir,description,1);add(dir,description,2);list(dir,row+"2\tOPEN\t"+description+"\n"); }
            case "invalid_command","invalid_args","invalid_id","invalid_description" -> {
                add(dir,description,1);
                List<List<String>> inputs=switch(name) {
                    case "invalid_command" -> List.of(List.of("--data-dir",dir,"unknown"),List.of("--data-dir",dir));
                    case "invalid_args" -> List.of(List.of(),List.of("list"),List.of("--data-dir"),List.of("--bad-option",dir,"list"),List.of("--data-dir","","list"),List.of("--data-dir",dir,"list","extra"),List.of("--data-dir",dir,"add"),List.of("--data-dir",dir,"add","one","two"),List.of("--data-dir",dir,"complete"),List.of("--data-dir",dir,"complete","1","extra"));
                    case "invalid_id" -> List.of("0","-1","NaN","1.5","999999999999999999999999999",Integer.toString(2+random.nextInt(1000))).stream().map(s->List.of("--data-dir",dir,"complete",s)).toList();
                    default -> List.of("","   ","with\ttab","with\nnewline","with\rreturn").stream().map(s->List.of("--data-dir",dir,"add",s)).toList();
                };
                for(var args:inputs) { invalid(args.toArray(String[]::new));list(dir,row); }
                add(dir,description+" after invalid",2);list(dir,row+"2\tOPEN\t"+description+" after invalid\n");
            }
            case "failed_write" -> { var r=raw("--data-dir","/opt/forbidden-task-data","add",description);if(r.exitCode()!=1 || !r.stdout().isEmpty() || r.stderr().isBlank()) throw new Failure("candidate:storage_failure_contract"); }
            default -> throw new IllegalArgumentException("Unknown case");
        }
    }
    static void pause(long deadline) {
        if(System.currentTimeMillis()>=deadline) throw new Failure("runtime:evaluation_deadline");
        try { Thread.sleep(Math.min(25,Math.max(1,deadline-System.currentTimeMillis()))); }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw new Failure("runtime:interrupted"); }
    }
}
