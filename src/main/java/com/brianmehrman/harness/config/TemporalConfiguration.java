package com.brianmehrman.harness.config;

import com.brianmehrman.harness.execution.CodingWorkflowImpl;
import com.brianmehrman.harness.execution.RunActivitiesImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@Profile("api | worker")
@EnableScheduling
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
        return WorkerFactory.newInstance(client);
    }

    @Bean
    @Profile("worker")
    ApplicationRunner startCodingWorker(WorkerFactory factory, RunActivitiesImpl activities,
            @Value("${harness.temporal.task-queue:coding-v1}") String taskQueue) {
        return args -> {
            var worker = factory.newWorker(taskQueue);
            worker.registerWorkflowImplementationTypes(CodingWorkflowImpl.class);
            worker.registerActivitiesImplementations(activities);
            factory.start();
        };
    }
}
