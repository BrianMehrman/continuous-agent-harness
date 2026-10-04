package com.brianmehrman.harness.runner;

import com.brianmehrman.harness.workspace.WorkspacePathPolicy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.apache.commons.compress.archivers.tar.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Trusted Docker control plane. Candidate strings are only tar content, never shell code. */
public class DockerCommandClient {
    static final String OWNER="continuous-agent-harness-v1";
    static final int LOG_LIMIT=262144, ARTIFACT_LIMIT=16777216, REPORT_LIMIT=4194304;
    private final JsonMapper json=JsonMapper.builder().build();
    public record Container(String id,String state,int exitCode,long finishedAt) {}
    record Output(byte[] logs,boolean truncated,byte[] artifact,String artifactError,byte[] reports) {}
    static class Unavailable extends RuntimeException { Unavailable(String message) { super(message); } }
    static class Collision extends RuntimeException { Collision() { super("Container ownership mismatch"); } }
    record Reply(int code,byte[] out,String error) {}

    Optional<Container> inspect(String name,String hash) {
        Reply reply=command(List.of("container","inspect",name),null,65536);
        if(reply.code()!=0) {
            if(reply.error().contains("No such container") || reply.error().contains("No such object")) return Optional.empty();
            throw new Unavailable("Docker inspect unavailable");
        }
        JsonNode node=json.readTree(reply.out()).get(0);
        if(!OWNER.equals(node.path("Config").path("Labels").path("harness.owner").asText())
                || !hash.equals(node.path("Config").path("Labels").path("harness.input").asText())) throw new Collision();
        return Optional.of(new Container(node.path("Id").asText(),node.path("State").path("Status").asText(),node.path("State").path("ExitCode").asInt(),java.time.Instant.parse(node.path("State").path("FinishedAt").asText()).toEpochMilli()));
    }
    void create(String name,String hash,String image,Operation operation,long deadline) {
        List<String> args=new ArrayList<>(List.of("create","--name",name,"--pull","never",
            "--label","harness.owner="+OWNER,"--label","harness.input="+hash,
            "--network","none","--read-only","--user","1000:1000","--cpus","2",
            "--memory","1g","--memory-swap","1g","--pids-limit","128","--cap-drop","ALL",
            "--security-opt","no-new-privileges:true","--restart","no",
            "--log-driver","local","--log-opt","max-size=32m","--log-opt","max-file=1","--log-opt","compress=false",
            "--tmpfs","/work:rw,nosuid,nodev,size=512m,mode=1777",
            "--tmpfs","/tmp:rw,nosuid,nodev,size=64m,mode=1777",
            "--tmpfs","/control:rw,nosuid,nodev,size=1m,mode=0755",
            "--env","HARNESS_OPERATION="+operation.name(),"--env","HARNESS_DEADLINE_EPOCH_MILLIS="+deadline,image));
        Reply reply=command(args,null,65536);
        if(reply.code()!=0 && inspect(name,hash).isEmpty()) throw new Unavailable("Docker create failed");
    }
    void start(Container container) { require(command(List.of("start",container.id()),null,65536)); }
    void stage(Container container,Map<String,String> files) {
        require(command(List.of("exec","-i","--user","0",container.id(),"/bin/sh","/opt/runner/stage.sh"),archive(files),65536));
    }
    void stop(Container container) { require(command(List.of("kill",container.id()),null,65536)); }
    void remove(Container container) { require(command(List.of("rm",container.id()),null,65536)); }
    Output output(Container container) {
        Reply reply=command(List.of("logs",container.id()),null,30*1024*1024);
        require(reply);
        return decode(reply.out());
    }
    static Output decode(byte[] bytes) {
        String text=new String(bytes,StandardCharsets.US_ASCII);
        if(!text.endsWith("\n") || text.indexOf('\n')!=text.length()-1) throw new IllegalArgumentException("Invalid runner envelope");
        String[] fields=text.substring(0,text.length()-1).split("\t",-1);
        if(fields.length!=6 || !fields[0].equals("HARNESS_RESULT_V1")
                || !Set.of("true","false").contains(fields[2])
                || !Set.of("","missing","oversized","nonregular","unreadable").contains(fields[4])) throw new IllegalArgumentException("Invalid runner envelope");
        return new Output(decodeField(fields[1],LOG_LIMIT),Boolean.parseBoolean(fields[2]),
                decodeField(fields[3],ARTIFACT_LIMIT),fields[4],decodeField(fields[5],REPORT_LIMIT));
    }
    private static byte[] decodeField(String text,int limit) {
        if(text.length()>4*((limit+2)/3)) throw new IllegalArgumentException("Runner output limit exceeded");
        byte[] bytes=Base64.getDecoder().decode(text);
        if(bytes.length>limit) throw new IllegalArgumentException("Runner output limit exceeded");
        return bytes;
    }
    static byte[] archive(Map<String,String> files) {
        WorkspacePathPolicy.validateSnapshot(files);
        try {
            var bytes=new ByteArrayOutputStream();
            try(var tar=new TarArchiveOutputStream(bytes,"UTF-8")) {
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                tar.setAddPaxHeadersForNonAsciiNames(true);
                for(var entry:new TreeMap<>(files).entrySet()) {
                    String path=entry.getKey();
                    if(!path.equals("README.md") && !path.startsWith("src/main/java/") && !path.startsWith("src/test/java/")) continue;
                    WorkspacePathPolicy.validateWritePath(path);
                    byte[] body=entry.getValue().getBytes(StandardCharsets.UTF_8);
                    var file=new TarArchiveEntry(path); file.setSize(body.length); file.setMode(0444); file.setLastModifiedTime(java.nio.file.attribute.FileTime.fromMillis(0));
                    tar.putArchiveEntry(file); tar.write(body); tar.closeArchiveEntry();
                }
            }
            return bytes.toByteArray();
        } catch(IOException e) { throw new IllegalStateException("Cannot stage snapshot",e); }
    }
    private static void require(Reply reply) { if(reply.code()!=0) throw new Unavailable("Docker command failed"); }
    Reply command(List<String> arguments,byte[] input,int limit) {
        var args=new ArrayList<String>(); args.add("docker"); args.addAll(arguments);
        Process process=null;
        var pool=Executors.newVirtualThreadPerTaskExecutor();
        try {
            process=new ProcessBuilder(args).start(); final Process running=process;
            Future<byte[]> out=pool.submit(() -> bounded(running.getInputStream(),limit));
            Future<byte[]> err=pool.submit(() -> bounded(running.getErrorStream(),65536));
            Future<?> writer=pool.submit(() -> { try(var stream=running.getOutputStream()) { if(input!=null) stream.write(input); } return null; });
            if(!process.waitFor(10,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new Unavailable("Docker command timed out"); }
            writer.get(1,TimeUnit.SECONDS);
            return new Reply(process.exitValue(),out.get(1,TimeUnit.SECONDS),new String(err.get(1,TimeUnit.SECONDS),StandardCharsets.UTF_8));
        } catch(ExecutionException e) {
            if(e.getCause() instanceof IllegalArgumentException invalid) throw invalid;
            throw new Unavailable("Docker command unavailable");
        }
        catch(IOException | TimeoutException e) { throw new Unavailable("Docker command unavailable"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new Unavailable("Docker command interrupted"); }
        finally {
            if(process!=null && process.isAlive()) process.destroyForcibly();
            pool.shutdownNow();
        }
    }
    static byte[] bounded(InputStream input,int limit) throws IOException {
        try(input; var bytes=new ByteArrayOutputStream()) {
            byte[] block=new byte[8192]; int read; boolean overflow=false;
            while((read=input.read(block))!=-1) { int keep=Math.min(read,limit-bytes.size()); bytes.write(block,0,keep); overflow|=keep<read; }
            if(overflow) throw new IllegalArgumentException("Docker output exceeds limit");
            return bytes.toByteArray();
        }
    }
}
