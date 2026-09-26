package com.brianmehrman.harness.config;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("api | worker")
public class TemporalConfiguration {
    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs workflowServiceStubs(
            @Value("${harness.temporal.target:127.0.0.1:7233}") String target) {
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
    }

    @Bean
    WorkflowClient workflowClient(WorkflowServiceStubs service,
            @Value("${harness.temporal.namespace:harness}") String namespace) {
        return WorkflowClient.newInstance(service,
                WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
    }

    @Bean(destroyMethod = "shutdown")
    @Profile("worker")
    WorkerFactory workerFactory(WorkflowClient client) {
        // Task 7 will register the coding workflow and start polling after registration.
        return WorkerFactory.newInstance(client);
    }
}
