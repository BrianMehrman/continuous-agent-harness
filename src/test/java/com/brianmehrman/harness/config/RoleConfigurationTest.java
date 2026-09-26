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
                    "spring.datasource.password=test-only",
                    "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/harness");

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"api,worker", "api,runner", "worker,runner", "api,worker,runner", "unrelated"})
    void rejectsInvalidRoleSelection(String profiles) {
        contexts.withPropertyValues("spring.profiles.active=" + profiles).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("Exactly one process role");
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "   "})
    void rejectsEmptyDatabasePassword(String password) {
        contexts.withPropertyValues("spring.profiles.active=api", "spring.datasource.password=" + password)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("Database password is required");
                });
    }

    @Test
    void acceptsAnAdditionalNonRoleProfile() {
        contexts.withPropertyValues("spring.profiles.active=runner,local").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(WorkflowClient.class);
        });
    }

    @Test
    void rejectsMissingDatabasePassword() {
        new ApplicationContextRunner().withUserConfiguration(HarnessApplication.class)
                .withPropertyValues("spring.profiles.active=api", "spring.flyway.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("Database password is required");
                });
    }

    @Test
    void acceptsDefaultApiRole() {
        contexts.withPropertyValues("spring.profiles.default=api").run(context -> assertThat(context).hasNotFailed());
    }

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
