package com.brianmehrman.harness.config;

import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.worker.WorkerDeploymentOptions;
import io.temporal.worker.WorkerOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Identifies a worker build so Temporal can keep existing runs on their original code. */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
public class WorkerDeploymentConfiguration {
    @Bean
    WorkerOptions codingWorkerOptions(@Value("${harness.temporal.build-id:}") String buildId) {
        if (!buildId.matches("sha256:[a-f0-9]{64}"))
            throw new IllegalArgumentException("Worker build ID must be sha256:<64 lowercase hex digits>");
        return WorkerOptions.newBuilder().setDeploymentOptions(
                WorkerDeploymentOptions.newBuilder()
                        .setUseVersioning(true)
                        .setVersion(new WorkerDeploymentVersion("coding-harness", buildId))
                        .build()).build();
    }
}
