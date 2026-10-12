package com.brianmehrman.harness.config;

import com.brianmehrman.harness.HarnessApplication;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RoleConfigurationTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(HarnessApplication.class)
            .withPropertyValues("spring.flyway.enabled=false",
                    "spring.datasource.password=test-only",
                    "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/harness",
                    "harness.temporal.build-id=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

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

    @org.junit.jupiter.api.Test
    void rejectsMissingDatabasePassword() {
        new ApplicationContextRunner().withUserConfiguration(HarnessApplication.class)
                .withPropertyValues("spring.profiles.active=api", "spring.flyway.enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("Database password is required");
                });
    }

}
