package com.brianmehrman.harness.workspace;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("runner")
class WorkspaceStoreIT {
    @Autowired WorkspaceStore store;
    @Autowired JdbcTemplate jdbc;
    private String run() { return "workspace-it-" + UUID.randomUUID(); }
    private WriteRequest edit(String run, String id, SnapshotRef parent, String text) {
        return new WriteRequest(run, id, parent, "README.md", "absent", text);
    }

    @Test void repeatedWriteReturnsOneSnapshotAndKeepsParent() {
        String run = run();
        var parent = store.seed(run,"task-tracker-v1");
        assertThat(store.seed(run,"task-tracker-v1")).isEqualTo(parent);
        var request = edit(run,"write-1",parent,"usage");
        var first = store.write(request);
        assertThat(store.write(request)).isEqualTo(first);
        assertThat(store.files(run,parent)).doesNotContainKey("README.md");
        assertThat(store.files(run,first)).containsEntry("README.md","usage");
        assertThatIllegalArgumentException().isThrownBy(() -> store.write(edit(run,"write-1",parent,"different")));
        assertThat(jdbc.queryForObject("select count(*) from workspace_write_receipt where run_id=?",Integer.class,run)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select parent_sha256 from workspace_write_receipt where run_id=?",String.class,run)).isEqualTo(parent.sha256());
    }

    @Test void rejectsOtherRunsProtectedFilesAndStaleHashes() {
        String run=run(); var parent=store.seed(run,"task-tracker-v1");
        String other=run(); store.seed(other,"task-tracker-v1");
        var result=store.write(edit(run,"one",parent,"private"));
        assertThatIllegalArgumentException().isThrownBy(() -> store.files(other,result));
        assertThatIllegalArgumentException().isThrownBy(() -> store.write(edit(other,"two",result,"steal")));
        assertThatIllegalArgumentException().isThrownBy(() -> store.write(new WriteRequest(run,"bad",parent,"build.gradle","absent","attack")));
        assertThatIllegalArgumentException().isThrownBy(() -> store.write(edit(run,"stale",result,"stale")));
        assertThatIllegalArgumentException().isThrownBy(() -> store.write(new WriteRequest(run,"wrong",result,"README.md","0".repeat(64),"wrong")));
        var changed=store.write(new WriteRequest(run,"valid",result,"README.md",SnapshotHasher.content("private"),"new"));
        assertThat(store.files(run,changed)).containsEntry("README.md","new");
        assertThat(store.files(run,result)).containsEntry("README.md","private");
        assertThatIllegalArgumentException().isThrownBy(() -> store.seed(run,"unknown-version"));
    }

    @Test void starterIsUnimplementedAndReadsAreDefensive() {
        String run=run(); var ref=store.seed(run,"task-tracker-v1");
        var files=store.files(run,ref);
        assertThat(files.get("src/main/java/example/tasktracker/TaskTracker.java")).contains("UnsupportedOperationException");
        assertThat(files).containsKeys("build.gradle","REQUIREMENTS.md","gradle/wrapper/gradle-wrapper.jar.sha256");
        assertThat(files).doesNotContainKey("gradle/wrapper/gradle-wrapper.jar");
        assertThat(files.get("REQUIREMENTS.md")).contains("Storage failure","Invalid input");
        assertThatThrownBy(() -> files.put("README.md","changed")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void concurrentRetriesReturnTheSameReceipt() throws Exception {
        String run=run(); var parent=store.seed(run,"task-tracker-v1");
        var request=edit(run,"same",parent,"concurrent");
        try(var pool=Executors.newFixedThreadPool(4)) {
            var gate=new CountDownLatch(1);
            List<Future<SnapshotRef>> results=new ArrayList<>();
            for(int i=0;i<4;i++) results.add(pool.submit(() -> { gate.await(); return store.write(request); }));
            gate.countDown();
            var expected=results.getFirst().get(15,TimeUnit.SECONDS);
            for(var result:results) assertThat(result.get(15,TimeUnit.SECONDS)).isEqualTo(expected);
        }
        assertThat(jdbc.queryForObject("select count(*) from workspace_write_receipt where run_id=?",Integer.class,run)).isEqualTo(1);
    }

    @Test void concurrentConflictingInputsCannotLeaveAnOrphanSnapshot() throws Exception {
        String run=run(); var parent=store.seed(run,"task-tracker-v1");
        String first="a-"+UUID.randomUUID(), second="b-"+UUID.randomUUID();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            List<Future<SnapshotRef>> results=new ArrayList<>();
            for(String text:List.of(first,second)) results.add(pool.submit(() -> { gate.await(); return store.write(edit(run,"collision",parent,text)); }));
            gate.countDown();
            int successes=0, conflicts=0;
            for(var result:results) {
                try { result.get(15,TimeUnit.SECONDS); successes++; }
                catch(ExecutionException e) { assertThat(e.getCause()).isInstanceOf(IllegalArgumentException.class); conflicts++; }
            }
            assertThat(successes).isEqualTo(1); assertThat(conflicts).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from run_snapshot where run_id=?",Integer.class,run)).isEqualTo(2);
        int persisted=0;
        for(String text:List.of(first,second)) {
            var files=new TreeMap<>(store.files(run,parent)); files.put("README.md",text);
            persisted+=jdbc.queryForObject("select count(*) from workspace_snapshot where sha256=?",Integer.class,SnapshotHasher.snapshot(files));
        }
        assertThat(persisted).isEqualTo(1);
    }

    @Test void totalAndFileCountQuotasAreTransactional() {
        String run=run(); var parent=store.seed(run,"task-tracker-v1");
        int available=64-store.files(run,parent).size();
        for(int i=0;i<available;i++) parent=store.write(new WriteRequest(run,"file-"+i,parent,"src/main/java/F"+i+".java","absent",""));
        SnapshotRef full=parent;
        assertThatIllegalArgumentException().isThrownBy(() -> store.write(edit(run,"too-many",full,"x")));
        assertThat(store.files(run,full)).hasSize(64);
        String other=run(); var bytes=store.seed(other,"task-tracker-v1");
        for(int i=0;i<15;i++) bytes=store.write(new WriteRequest(other,"large-"+i,bytes,"src/main/java/L"+i+".java","absent","x".repeat(65536)));
        SnapshotRef nearlyFull=bytes;
        assertThatIllegalArgumentException().isThrownBy(() -> store.write(new WriteRequest(other,"overflow",nearlyFull,"src/main/java/Overflow.java","absent","x".repeat(65536))));
        assertThat(jdbc.queryForObject("select count(*) from workspace_write_receipt where run_id=? and invocation_id='overflow'",Integer.class,other)).isZero();
    }

    @Test void pathCollisionsInEitherOrderLeaveNoReceipt() {
        for(boolean reverse:List.of(false,true)) {
            String run=run(); var parent=store.seed(run,"task-tracker-v1");
            String first=reverse?"src/main/java/Foo.java/Bar.java":"src/main/java/Foo.java";
            String second=reverse?"src/main/java/Foo.java":"src/main/java/Foo.java/Bar.java";
            var ref=store.write(new WriteRequest(run,"first",parent,first,"absent",""));
            assertThatIllegalArgumentException().isThrownBy(() -> store.write(new WriteRequest(run,"second",ref,second,"absent","")));
            assertThat(jdbc.queryForObject("select count(*) from workspace_write_receipt where run_id=?",Integer.class,run)).isEqualTo(1);
            assertThat(store.files(run,ref)).containsKey(first).doesNotContainKey(second);
        }
    }

    @Test void quotaFailureLeavesNoReceipt() {
        String run=run(); var parent=store.seed(run,"task-tracker-v1");
        assertThatIllegalArgumentException().isThrownBy(() -> store.write(edit(run,"oversize",parent,"a".repeat(65537))));
        assertThat(jdbc.queryForObject("select count(*) from workspace_write_receipt where run_id=?",Integer.class,run)).isZero();
    }

    @Test void connectionLossRollsBackSnapshotAndReceipt() throws Exception {
        String run=run(); var parent=store.seed(run,"task-tracker-v1");
        String marker="rollback-"+UUID.randomUUID();
        var files=new TreeMap<>(store.files(run,parent)); files.put("README.md",marker);
        String digest=SnapshotHasher.snapshot(files);
        jdbc.execute("create function workspace_it_pause() returns trigger language plpgsql as $$ begin if NEW.files_json->>'README.md' = '"+marker+"' then perform pg_sleep(20); end if; return NEW; end $$");
        jdbc.execute("create trigger workspace_it_pause after insert on workspace_snapshot for each row execute function workspace_it_pause()");
        try(var pool=Executors.newSingleThreadExecutor()) {
            var future=pool.submit(() -> store.write(edit(run,"interrupted",parent,marker)));
            try {
                Integer pid=null; long deadline=System.nanoTime()+Duration.ofSeconds(8).toNanos();
                while(pid==null && System.nanoTime()<deadline) {
                    var pids=jdbc.queryForList("select pid from pg_stat_activity where usename=current_user and wait_event='PgSleep' and query like 'insert into workspace_snapshot%'",Integer.class);
                    if(!pids.isEmpty()) pid=pids.getFirst(); else Thread.sleep(25);
                }
                assertThat(pid).as("writer paused after snapshot insert").isNotNull();
                assertThat(jdbc.queryForObject("select pg_terminate_backend(?)",Boolean.class,pid)).isTrue();
                assertThatThrownBy(() -> future.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
                assertThat(jdbc.queryForObject("select count(*) from workspace_snapshot where sha256=?",Integer.class,digest)).isZero();
                assertThat(jdbc.queryForObject("select count(*) from workspace_write_receipt where run_id=?",Integer.class,run)).isZero();
                assertThat(store.files(run,parent)).doesNotContainKey("README.md");
            } finally {
                jdbc.execute("drop trigger workspace_it_pause on workspace_snapshot");
                jdbc.execute("drop function workspace_it_pause()");
            }
        }
        assertThat(store.write(edit(run,"interrupted",parent,marker)).sha256()).isEqualTo(digest);
    }
}
