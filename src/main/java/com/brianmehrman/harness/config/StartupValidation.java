package com.brianmehrman.harness.config;

import java.util.Arrays;
import java.util.Set;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class StartupValidation {
    private static final Set<String> ROLES = Set.of("api", "worker", "runner");

    @Bean
    static BeanFactoryPostProcessor validateStartup(Environment environment) {
        return beanFactory -> {
            String[] profiles = environment.getActiveProfiles();
            if (profiles.length == 0) {
                profiles = environment.getDefaultProfiles();
            }
            long roles = Arrays.stream(profiles).filter(ROLES::contains).distinct().count();
            if (roles != 1) {
                throw new IllegalStateException("Exactly one process role (api, worker, runner) is required");
            }
            String password = environment.getProperty("spring.datasource.password");
            if (password == null || password.isBlank()) {
                throw new IllegalStateException("Database password is required; configure the application secret file");
            }
        };
    }
}
