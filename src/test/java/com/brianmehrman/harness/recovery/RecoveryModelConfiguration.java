package com.brianmehrman.harness.recovery;

import com.brianmehrman.harness.execution.ModelAdapterFactory;
import com.brianmehrman.harness.model.ModelReply;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Blocks the first model invocation at a durable Activity boundary; later calls are deterministic. */
@Configuration(proxyBeanMethods = false)
@Profile("recovery-test")
class RecoveryModelConfiguration {
    @Bean
    @Primary
    ModelAdapterFactory recoveryTestModelAdapterFactory(
            @Value("${task9.recovery.marker}") String markerPath) {
        return profile -> call -> {
            if (call.invocationId().endsWith(":turn-1") && call.attempt() == 1) {
                try {
                    Path marker = Path.of(markerPath);
                    Files.createDirectories(marker.toAbsolutePath().getParent());
                    Files.createFile(marker);
                    while (true) Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Recovery fixture interrupted", interrupted);
                } catch (java.io.IOException failure) {
                    throw new IllegalStateException("Recovery fixture barrier unavailable", failure);
                }
            }
            return new ModelReply("scripted text without tools", List.of(), 1L, 1L, "stop");
        };
    }
}
