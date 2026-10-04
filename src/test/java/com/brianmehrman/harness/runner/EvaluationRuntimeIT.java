package com.brianmehrman.harness.runner;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class EvaluationRuntimeIT {
    @Test void artifactStagingRetriesAfterEitherReadOnlyRenameBoundary() {
        var docker=new EvaluationDocker();var cli=new DockerCommandClient();
        String id=InvocationHasher.digest(UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            docker.ensureVolume(id,true);docker.ensureVolume(id,false);
            docker.createHolder(id,System.getenv("HARNESS_RUNNER_IMAGE"),System.currentTimeMillis()+30000);
            var holder=docker.inspect(docker.holder(id),id).orElseThrow();docker.start(holder);
            docker.stage(holder,new byte[]{1,2,3});docker.stage(holder,new byte[]{4,5,6});
            var partial=cli.command(List.of("exec","--user","0",holder.id(),"/bin/sh","-c","echo stale > /artifact/task-tracker.jar.next; chmod 0444 /artifact/task-tracker.jar.next"),null,1000);
            assertThat(partial.code()).isZero();
            docker.stage(holder,new byte[]{7,8,9});
            var content=cli.command(List.of("exec",holder.id(),"cat","/artifact/task-tracker.jar"),null,1000);
            assertThat(content.code()).isZero();assertThat(content.out()).containsExactly(7,8,9);
            var write=cli.command(List.of("exec",holder.id(),"/bin/sh","-c","echo tamper > /artifact/task-tracker.jar"),null,1000);
            assertThat(write.code()).isNotZero();
            // A full admitted artifact must fit both old and next copies during retry.
            docker.stage(holder,new byte[16*1024*1024]);
            docker.stage(holder,new byte[16*1024*1024]);
            var size=cli.command(List.of("exec",holder.id(),"stat","-c","%s","/artifact/task-tracker.jar"),null,1000);
            assertThat(new String(size.out(),java.nio.charset.StandardCharsets.US_ASCII).strip()).isEqualTo("16777216");
        } finally {
            var holder=docker.inspect(docker.holder(id),id);
            if(holder.isPresent()) { if(holder.get().state().equals("running")) docker.kill(holder.get());docker.remove(holder.get()); }
            docker.removeVolume(id,true);docker.removeVolume(id,false);
        }
    }
}
