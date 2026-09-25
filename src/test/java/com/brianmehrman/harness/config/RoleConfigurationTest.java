package com.brianmehrman.harness.config;

import com.brianmehrman.harness.HarnessApplication;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RoleConfigurationTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(HarnessApplication.class)
            .withPropertyValues("spring.flyway.enabled=false",
                    "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/harness");

    @Test
    void runnerDoesNotCreateTemporalResources() {
        contexts.withPropertyValues("spring.profiles.active=runner").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(WorkflowClient.class);
            assertThat(context).doesNotHaveBean(WorkflowServiceStubs.class);
            assertThat(context).doesNotHaveBean(WorkerFactory.class);
        });
    }

    @Test
    void apiCanSubmitWorkWithoutCreatingAWorker() {
        contexts.withPropertyValues("spring.profiles.active=api").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WorkflowClient.class);
            assertThat(context).doesNotHaveBean(WorkerFactory.class);
        });
    }

    @Test
    void workerUsesTheConfiguredClientAndClosesItsResources() {
        WorkflowServiceStubs[] stubs = new WorkflowServiceStubs[1];
        WorkerFactory[] factories = new WorkerFactory[1];
        contexts.withPropertyValues("spring.profiles.active=worker",
                "harness.temporal.target=127.0.0.1:17233",
                "harness.temporal.namespace=role-test").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WorkerFactory.class);
            var client = context.getBean(WorkflowClient.class);
            assertThat(client.getOptions().getNamespace()).isEqualTo("role-test");
            stubs[0] = context.getBean(WorkflowServiceStubs.class);
            factories[0] = context.getBean(WorkerFactory.class);
            assertThat(stubs[0].getOptions().getTarget()).isEqualTo("127.0.0.1:17233");
            assertThat(factories[0].getWorkflowClient()).isSameAs(client);
        });
        assertThat(stubs[0].isShutdown()).isTrue();
        assertThat(factories[0].isShutdown()).isTrue();
    }
}
