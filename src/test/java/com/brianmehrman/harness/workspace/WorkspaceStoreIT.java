package com.brianmehrman.harness.workspace;

import java.util.*;
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

}
