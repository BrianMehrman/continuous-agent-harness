package com.brianmehrman.harness.runner;
import com.brianmehrman.harness.workspace.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="harness.runner.enabled=false")
@ActiveProfiles("runner")
class RunnerRecoveryIT {
 @Autowired Runner runner;
 @Autowired WorkspaceStore workspaces;
 @Autowired JdbcTemplate jdbc;
 final DockerCommandClient docker=new DockerCommandClient();
 final List<Process> processes=new ArrayList<>();
 @AfterEach void stopProcesses() throws Exception { for(var p:processes) { p.destroyForcibly(); p.waitFor(10,TimeUnit.SECONDS); } }
 Process launch(String boundary,String id,Path marker) throws Exception { return launch(boundary,id,marker,null); }
 Process launch(String boundary,String id,Path marker,String dockerHost) throws Exception {
  var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("runner.test.classpath"),RunnerRecoveryProcess.class.getName(),"--spring.profiles.active=runner,recovery-test","--harness.runner.enabled=true");
  if(dockerHost!=null) { builder.environment().remove("DOCKER_CONTEXT");builder.environment().put("DOCKER_HOST",dockerHost); }
  builder.environment().put("RUNNER_TEST_BOUNDARY",boundary); builder.environment().put("RUNNER_TEST_ID",id); builder.environment().put("RUNNER_TEST_MARKER",marker.toAbsolutePath().toString());
  Files.createDirectories(Path.of("target/runner-tests"));
  builder.redirectErrorStream(true).redirectOutput(Path.of("target/runner-tests",id+"-"+processes.size()+".log").toFile());
  Process p=builder.start();processes.add(p);return p;
 }
 InvocationRequest request(Operation operation,long duration,String source) {
  String run="runner-recovery-"+UUID.randomUUID(),id=UUID.randomUUID().toString();
  var snapshot=workspaces.seed(run,"task-tracker-v1");
  if(source!=null) snapshot=workspaces.write(new WriteRequest(run,"fixture",snapshot,"src/test/java/ProbeTest.java","absent",source));
  return new InvocationRequest(run,id,snapshot,operation,System.currentTimeMillis()+duration);
 }
 static void await(BooleanSupplier condition) throws Exception {
  long end=System.nanoTime()+Duration.ofSeconds(120).toNanos();
  while(!condition.getAsBoolean()) { if(System.nanoTime()>end) fail("Runner did not reach expected state within 120 seconds"); Thread.sleep(100); }
 }
 Map<String,Object> row(String id) { return jdbc.queryForMap("select * from runner_invocation where invocation_id=?",id); }
 Optional<DockerCommandClient.Container> container(String id) { var r=row(id);return docker.inspect((String)r.get("container_name"),(String)r.get("input_sha256")); }
 InvocationResult result(String id) throws Exception { await(() -> runner.result(id).isPresent());return runner.result(id).orElseThrow(); }
 @ParameterizedTest @ValueSource(strings={"AFTER_LEDGER_COMMIT","AFTER_CREATE","AFTER_START","AFTER_EXIT","AFTER_RESULT_COMMIT"})
 void resumesAcrossRealProcessDeath(String boundary) throws Exception {
  var request=request(Operation.BUILD,120000,null);String id=request.invocationId();
  runner.ensureStarted(request);runner.ensureStarted(request);
  Path marker=Path.of("target/runner-tests",id+".marker");
  Process first=launch(boundary,id,marker);await(() -> Files.exists(marker));
  String original=boundary.equals("AFTER_LEDGER_COMMIT")?null:container(id).orElseThrow().id();
  first.destroyForcibly();assertThat(first.waitFor(10,TimeUnit.SECONDS)).isTrue();
  runner.ensureStarted(request);launch("",id,marker);
  var receipt=result(id);
  assertThat(receipt.status()).isEqualTo("SUCCEEDED");assertThat(receipt.attempt()).isEqualTo(1);assertThat(receipt.uncertain()).isFalse();
  assertThat(receipt.artifactBlobId()).isNotNull();
  if(original!=null && container(id).isPresent()) assertThat(container(id).orElseThrow().id()).isEqualTo(original);
  runner.ensureStarted(request);assertThat(runner.result(id)).contains(receipt);
  assertOneCreate(receipt.inputSha256());
  await(() -> Boolean.TRUE.equals(row(id).get("cleaned")));
 }
 @Test void missingEvidenceAllowsOneFreshAttemptAndMarksUncertainty() throws Exception {
  var r=request(Operation.BUILD,120000,null);String id=r.invocationId();runner.ensureStarted(r);
  Path marker=Path.of("target/runner-tests",id+".marker");var first=launch("AFTER_EXIT",id,marker);await(() -> Files.exists(marker));
  var lost=container(id).orElseThrow();first.destroyForcibly();first.waitFor(10,TimeUnit.SECONDS);docker.remove(lost);
  launch("",id,marker);var receipt=result(id);
  assertThat(receipt.status()).isEqualTo("SUCCEEDED");assertThat(receipt.attempt()).isEqualTo(2);assertThat(receipt.uncertain()).isTrue();
  await(() -> Boolean.TRUE.equals(row(id).get("cleaned")));
 }
 @Test void cancellationStopsActualCandidateAndDoesNotRetry() throws Exception {
  var r=request(Operation.TEST,120000,"import org.junit.jupiter.api.Test; class ProbeTest { @Test void waitForever() throws Exception { Thread.sleep(300000); } }");
  runner.ensureStarted(r);String id=r.invocationId();Path marker=Path.of("target/runner-tests",id+".marker");
  var first=launch("AFTER_START",id,marker);await(() -> Files.exists(marker));first.destroyForcibly();first.waitFor(10,TimeUnit.SECONDS);
  runner.cancel(id);launch("",id,marker);assertThat(result(id).status()).isEqualTo("CANCELLED");
  await(() -> Boolean.TRUE.equals(row(id).get("cleaned")));
 }
 @Test void absoluteDeadlineSurvivesRunnerDowntime() throws Exception {
  var r=request(Operation.TEST,10000,"import org.junit.jupiter.api.Test; class ProbeTest { @Test void waitForever() throws Exception { Thread.sleep(300000); } }");
  runner.ensureStarted(r);String id=r.invocationId();Path marker=Path.of("target/runner-tests",id+".marker");
  var first=launch("AFTER_START",id,marker);await(() -> Files.exists(marker));first.destroyForcibly();first.waitFor(10,TimeUnit.SECONDS);
  await(() -> System.currentTimeMillis()>r.deadlineEpochMillis());launch("",id,marker);
  assertThat(result(id).status()).isEqualTo("TIMED_OUT");assertThat(result(id).attempt()).isEqualTo(1);
  await(() -> Boolean.TRUE.equals(row(id).get("cleaned")));
 }
 @Test void candidateTestsRunOfflineWithIsolationAndBoundedLogs() throws Exception {
  var r=request(Operation.TEST,120000,"""
   import org.junit.jupiter.api.Test;
   import java.nio.file.*; import java.net.*;
   import static org.junit.jupiter.api.Assertions.*;
   class ProbeTest { @Test void isolation() throws Exception {
    assertFalse(Files.exists(Path.of("/var/run/docker.sock")));
    assertFalse(Files.exists(Path.of("/Users/brianmehrman")));
    assertFalse(Files.exists(Path.of("/.secrets")));
    assertThrows(Exception.class, () -> Files.writeString(Path.of("/work/project/build.gradle"),"attack"));
    assertThrows(Exception.class, () -> Files.writeString(Path.of("/control/ready"),"attack"));
    assertThrows(Exception.class, () -> { try(var s=new Socket()) { s.connect(new InetSocketAddress("1.1.1.1",443),500); } });
    assertEquals("128",Files.readString(Path.of("/sys/fs/cgroup/pids.max")).trim());
    assertEquals("1073741824",Files.readString(Path.of("/sys/fs/cgroup/memory.max")).trim());
    for(int i=0;i<10000;i++) System.out.println("bounded-output-".repeat(10));
   } }
   """);
  runner.ensureStarted(r);String id=r.invocationId();launch("",id,Path.of("target/runner-tests",id+".marker"));
  var receipt=result(id);assertThat(receipt.status()).isEqualTo("SUCCEEDED");assertThat(receipt.truncated()).isTrue();
  assertThat(jdbc.queryForObject("select size_bytes from artifact_blob where sha256=?",Integer.class,receipt.logBlobId())).isEqualTo(262144);
  assertThat(row(id).get("reports_blob_id")).isNotNull();await(() -> Boolean.TRUE.equals(row(id).get("cleaned")));
 }
 @Test void compileFailureIsAResultWithoutInfrastructureRetry() throws Exception {
  var r=request(Operation.TEST,120000,"not valid java");runner.ensureStarted(r);
  launch("",r.invocationId(),Path.of("target/runner-tests",r.invocationId()+".marker"));
  var receipt=result(r.invocationId());assertThat(receipt.status()).isEqualTo("FAILED");assertThat(receipt.attempt()).isEqualTo(1);assertThat(receipt.uncertain()).isFalse();
  await(() -> Boolean.TRUE.equals(row(r.invocationId()).get("cleaned")));
 }
 @Test void completedBuildSurvivesDowntimePastDeadline() throws Exception {
  var r=request(Operation.BUILD,25000,null);String id=r.invocationId();runner.ensureStarted(r);
  Path marker=Path.of("target/runner-tests",id+".marker");var first=launch("AFTER_EXIT",id,marker);await(() -> Files.exists(marker));
  first.destroyForcibly();first.waitFor(10,TimeUnit.SECONDS);await(() -> System.currentTimeMillis()>r.deadlineEpochMillis());
  launch("",id,marker);assertThat(result(id).status()).isEqualTo("SUCCEEDED");
  await(() -> Boolean.TRUE.equals(row(id).get("cleaned")));
 }
 @Test void secondLossStopsRetries() throws Exception {
  var r=request(Operation.BUILD,120000,null);String id=r.invocationId();runner.ensureStarted(r);
  Path marker=Path.of("target/runner-tests",id+".marker");
  for(int attempt=1;attempt<=2;attempt++) {
   Files.deleteIfExists(marker);var process=launch("AFTER_EXIT",id,marker);await(() -> Files.exists(marker));
   var lost=container(id).orElseThrow();process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);docker.remove(lost);
  }
  launch("",id,marker);var receipt=result(id);assertThat(receipt.status()).isEqualTo("UNCERTAIN");assertThat(receipt.attempt()).isEqualTo(2);assertThat(receipt.uncertain()).isTrue();
  await(() -> Boolean.TRUE.equals(row(id).get("cleaned")));
 }
 @Test void twoSupervisorsStillExecuteOneContainer() throws Exception {
  var r=request(Operation.BUILD,120000,null);String id=r.invocationId();runner.ensureStarted(r);
  Path marker=Path.of("target/runner-tests",id+".marker");launch("",id,marker);launch("",id,marker);
  assertThat(result(id).status()).isEqualTo("SUCCEEDED");assertThat(result(id).attempt()).isEqualTo(1);
  await(() -> Boolean.TRUE.equals(row(id).get("cleaned")));
  assertOneCreate((String)row(id).get("input_sha256"));
 }
 static void assertOneCreate(String hash) throws Exception {
  var process=new ProcessBuilder("docker","events","--since",Long.toString(System.currentTimeMillis()/1000-180),"--until",Long.toString(System.currentTimeMillis()/1000+1),"--filter","type=container","--filter","event=create","--filter","label=harness.input="+hash,"--format","{{.Actor.ID}}").start();
  assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
  assertThat(new String(process.getInputStream().readAllBytes()).lines().filter(line -> !line.isBlank()).distinct().count()).isEqualTo(1);
 }

 @Test void unrelatedNameCollisionIsNeverRemoved() throws Exception {
  var r=request(Operation.BUILD,120000,null);String id=r.invocationId();runner.ensureStarted(r);
  String name=(String)row(id).get("container_name");
  var create=new ProcessBuilder("docker","create","--name",name,"--label","harness.owner=unrelated",System.getenv("HARNESS_RUNNER_IMAGE")).start();
  assertThat(create.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(create.exitValue()).isZero();
  String foreign=new String(create.getInputStream().readAllBytes()).trim();
  try {
   launch("",id,Path.of("target/runner-tests",id+".marker"));assertThat(result(id).status()).isEqualTo("FAILED");
   await(() -> Boolean.TRUE.equals(row(id).get("cleaned")));
   var inspect=new ProcessBuilder("docker","inspect","--format","{{.Id}}",foreign).start();assertThat(inspect.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(inspect.exitValue()).isZero();
   assertThat(new String(inspect.getInputStream().readAllBytes()).trim()).isEqualTo(foreign);
  } finally { var remove=new ProcessBuilder("docker","rm",foreign).start();assertThat(remove.waitFor(10,TimeUnit.SECONDS)).isTrue(); }
 }
 @Test void partialReadOnlyStagingCanBeRetried() throws Exception {
  var r=request(Operation.BUILD,120000,null);String id=r.invocationId();runner.ensureStarted(r);
  Path marker=Path.of("target/runner-tests",id+".marker");var first=launch("AFTER_CREATE",id,marker);await(() -> Files.exists(marker));
  first.destroyForcibly();first.waitFor(10,TimeUnit.SECONDS);var c=container(id).orElseThrow();docker.start(c);
  var partial=new ProcessBuilder("docker","exec","--user","0",c.id(),"/bin/sh","-c","mkdir -p /work/project/gradle; cp /opt/project/build.gradle /work/project/build.gradle; chmod 0444 /work/project/build.gradle; chmod 0555 /work/project/gradle").start();
  assertThat(partial.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(partial.exitValue()).isZero();
  launch("",id,marker);assertThat(result(id).status()).isEqualTo("SUCCEEDED");await(() -> Boolean.TRUE.equals(row(id).get("cleaned")));
 }

 @Test void resourceExhaustionIsContainedInCandidate() throws Exception {
  var r=request(Operation.TEST,120000,"""
   import org.junit.jupiter.api.Test; import java.util.*; import java.io.*;
   import static org.junit.jupiter.api.Assertions.*;
   class ProbeTest { @Test void bounded() throws Exception {
    var blocks=new ArrayList<byte[]>(); boolean memoryLimited=false;
    try { for(int i=0;i<128;i++) blocks.add(new byte[8*1024*1024]); }
    catch(OutOfMemoryError expected) { memoryLimited=true; }
    finally { blocks.clear(); System.gc(); }
    assertTrue(memoryLimited); System.out.println("MEMORY_LIMIT_ENFORCED");
    var children=new ArrayList<Process>();boolean processLimited=false;
    try { for(int i=0;i<160;i++) children.add(new ProcessBuilder("/bin/sleep","30").start()); }
    catch(IOException | OutOfMemoryError expected) { processLimited=true; }
    finally { for(var child:children) child.destroyForcibly(); }
    assertTrue(processLimited);System.out.println("PID_LIMIT_ENFORCED");
   } }
   """);
  runner.ensureStarted(r);String id=r.invocationId();launch("",id,Path.of("target/runner-tests",id+".marker"));
  var receipt=result(id);assertThat(receipt.status()).isEqualTo("SUCCEEDED");
  String logs=jdbc.queryForObject("select convert_from(content,'UTF8') from artifact_blob where sha256=?",String.class,receipt.logBlobId());
  assertThat(logs).contains("MEMORY_LIMIT_ENFORCED","PID_LIMIT_ENFORCED");await(() -> Boolean.TRUE.equals(row(id).get("cleaned")));
 }

 @Test void dockerOutageDoesNotFalselyConfirmCancellation() throws Exception {
  var r=request(Operation.BUILD,120000,null);String id=r.invocationId();runner.ensureStarted(r);runner.cancel(id);
  Path marker=Path.of("target/runner-tests",id+".marker");var unavailable=launch("",id,marker,"unix:///nonexistent-harness-test-socket");
  Path log=Path.of("target/runner-tests",id+"-0.log");
  await(() -> { try { return Files.readString(log).contains("Started RunnerRecoveryProcess"); } catch(Exception e) { return false; } });
  Thread.sleep(2500);assertThat(runner.result(id)).isEmpty();
  unavailable.destroyForcibly();unavailable.waitFor(10,TimeUnit.SECONDS);launch("",id,marker);
  assertThat(result(id).status()).isEqualTo("CANCELLED");await(() -> Boolean.TRUE.equals(row(id).get("cleaned")));
 }

 @Test void oversizedDockerLogsProduceTerminalReceiptAndCleanup() throws Exception {
  var r=request(Operation.BUILD,120000,null);String id=r.invocationId();runner.ensureStarted(r);
  var row=row(id);String name=(String)row.get("container_name"),hash=(String)row.get("input_sha256");
  // Test-owned malformed container evidence: bypass the trusted launcher's cap deliberately.
  var create=new ProcessBuilder("docker","create","--name",name,"--label","harness.owner="+DockerCommandClient.OWNER,"--label","harness.input="+hash,"--network","none","--read-only","--log-driver","local","--log-opt","max-size=32m","--log-opt","max-file=1","--log-opt","compress=false","--entrypoint","/bin/sh",System.getenv("HARNESS_RUNNER_IMAGE"),"-c","head -c 32505856 /dev/zero").start();
  assertThat(create.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(create.exitValue()).isZero();docker.start(container(id).orElseThrow());
  await(() -> container(id).orElseThrow().state().equals("exited"));
  launch("",id,Path.of("target/runner-tests",id+".marker"));
  assertThat(result(id).status()).isEqualTo("FAILED");await(() -> Boolean.TRUE.equals(row(id).get("cleaned")));
 }

}
