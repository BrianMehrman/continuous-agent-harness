package com.brianmehrman.harness.runner;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Docker-only transport. Arguments are argv data; the only shell program is fixed JAR staging. */
final class EvaluationDocker {
    static final String OWNER="continuous-agent-harness-evaluation-v1";
    private final DockerCommandClient client=new DockerCommandClient();
    private final JsonMapper json=JsonMapper.builder().build();
    record Container(String id,String state,int exit,long finishedAt) {}
    String holder(String session) { return "harness-eval-holder-"+session; }
    String candidate(String command) { return "harness-eval-command-"+command; }
    String volume(String session,boolean artifact) { return "harness-eval-"+(artifact?"jar-":"data-")+session; }
    private DockerCommandClient.Reply call(List<String> args,byte[] input,int limit) { return client.command(args,input,limit); }
    private void require(DockerCommandClient.Reply r) { if(r.code()!=0) throw new DockerCommandClient.Unavailable("Evaluation Docker operation unavailable"); }
    Optional<Container> inspect(String name,String identity) {
        var reply=call(List.of("inspect","--type","container",name),null,65536);
        if(reply.code()!=0) {
            if(reply.error().toLowerCase(Locale.ROOT).contains("no such")) return Optional.empty();
            throw new DockerCommandClient.Unavailable("Evaluation Docker inspect unavailable");
        }
        var node=json.readTree(reply.out()).get(0);
        labels(node.path("Config").path("Labels"),identity);
        String finished=node.path("State").path("FinishedAt").asText();
        return Optional.of(new Container(node.path("Id").asText(),node.path("State").path("Status").asText(),
            node.path("State").path("ExitCode").asInt(),java.time.Instant.parse(finished).toEpochMilli()));
    }
    void labels(JsonNode node,String identity) {
        if(!OWNER.equals(node.path("harness.owner").asText()) || !identity.equals(node.path("harness.input").asText()))
            throw new DockerCommandClient.Collision();
    }
    String options(boolean artifact) { return artifact?"size=34m,uid=0,gid=0,mode=0755,nosuid,nodev":"size=32m,uid=1000,gid=1000,mode=0700,nosuid,nodev"; }
    void ensureVolume(String session,boolean artifact) {
        String name=volume(session,artifact);
        var reply=call(List.of("volume","inspect",name),null,65536);
        if(reply.code()!=0) {
            if(!reply.error().toLowerCase(Locale.ROOT).contains("no such volume")) throw new DockerCommandClient.Unavailable("Volume inspect unavailable");
            require(call(List.of("volume","create","--driver","local","--label","harness.owner="+OWNER,"--label","harness.input="+session,
                "--opt","type=tmpfs","--opt","device=tmpfs","--opt","o="+options(artifact),name),null,65536));
            reply=call(List.of("volume","inspect",name),null,65536); require(reply);
        }
        var node=json.readTree(reply.out()).get(0); labels(node.path("Labels"),session);
        if(!node.path("Driver").asText().equals("local") || !node.path("Options").path("type").asText().equals("tmpfs")
            || !node.path("Options").path("device").asText().equals("tmpfs") || !node.path("Options").path("o").asText().equals(options(artifact)))
            throw new DockerCommandClient.Collision();
    }
    List<String> base(String name,String identity) {
        return new ArrayList<>(List.of("create","--name",name,"--pull","never","--label","harness.owner="+OWNER,
            "--label","harness.input="+identity,"--network","none","--read-only","--user","1000:1000","--cpus","2",
            "--memory","1g","--memory-swap","1g","--pids-limit","128","--cap-drop","ALL","--security-opt","no-new-privileges:true",
            "--restart","no","--log-driver","local","--log-opt","max-size=1m","--log-opt","max-file=1","--log-opt","compress=false",
            "--tmpfs","/tmp:rw,nosuid,nodev,size=16m,mode=1777"));
    }
    void createHolder(String session,String image,long deadline) {
        var args=base(holder(session),session);
        args.addAll(List.of("--mount","type=volume,source="+volume(session,true)+",target=/artifact,volume-nocopy",
            "--mount","type=volume,source="+volume(session,false)+",target=/data,volume-nocopy","--entrypoint","/bin/sleep",image,
            Long.toString(Math.max(1,(deadline-System.currentTimeMillis()+999)/1000))));
        var r=call(args,null,65536); if(r.code()!=0 && inspect(holder(session),session).isEmpty()) require(r);
    }
    void stage(Container holder,byte[] jar) {
        if(jar.length==0 || jar.length>16777216) throw new IllegalArgumentException("Invalid artifact size");
        require(call(List.of("exec","-i","--user","0",holder.id(),"/bin/sh","-c",
            "umask 022; rm -f /artifact/task-tracker.jar.next; cat > /artifact/task-tracker.jar.next && chmod 0444 /artifact/task-tracker.jar.next && mv -f /artifact/task-tracker.jar.next /artifact/task-tracker.jar"),jar,65536));
    }
    void createCandidate(String session,String command,String image,List<String> arguments) {
        if(arguments.size()>8 || arguments.stream().anyMatch(s->s==null || s.length()>2048 || s.indexOf('\0')>=0)) throw new IllegalArgumentException("Invalid runtime argv");
        var args=base(candidate(command),command);
        args.addAll(List.of("--mount","type=volume,source="+volume(session,true)+",target=/artifact,readonly,volume-nocopy",
            "--mount","type=volume,source="+volume(session,false)+",target=/data,volume-nocopy",
            "--env","LANG=C.UTF-8","--entrypoint","/usr/bin/timeout",image,"--signal=KILL","5",
            "/opt/java/openjdk/bin/java","-Xmx256m","-XX:ActiveProcessorCount=2","-jar","/artifact/task-tracker.jar"));
        args.addAll(arguments);
        var r=call(args,null,65536); if(r.code()!=0 && inspect(candidate(command),command).isEmpty()) require(r);
    }
    void start(Container c) { require(call(List.of("start",c.id()),null,65536)); }
    void kill(Container c) { require(call(List.of("kill",c.id()),null,65536)); }
    void remove(Container c) { require(call(List.of("rm",c.id()),null,65536)); }
    EvaluationRuntime.Result output(Container c) {
        try {
            var r=call(List.of("logs",c.id()),null,196608); require(r);
            return new EvaluationRuntime.Result(c.exit(),new String(r.out(),java.nio.charset.StandardCharsets.UTF_8),r.error(),false,"COMPLETE");
        } catch(IllegalArgumentException e) {
            return new EvaluationRuntime.Result(c.exit(),"","",true,"OUTPUT_LIMIT");
        }
    }
    void removeVolume(String session,boolean artifact) {
        String name=volume(session,artifact); var r=call(List.of("volume","inspect",name),null,65536);
        if(r.code()!=0 && r.error().toLowerCase(Locale.ROOT).contains("no such volume")) return;
        require(r); labels(json.readTree(r.out()).get(0).path("Labels"),session);
        require(call(List.of("volume","rm",name),null,65536));
    }
}
