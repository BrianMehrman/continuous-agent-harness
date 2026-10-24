package com.brianmehrman.harness.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import java.nio.file.Path;

class ModelConfigurationTest {
    @Test void liveSelectionRequiresExplicitModelAndDoesNotChooseDefault() {
        var config = new ModelConfiguration(new MockEnvironment());
        assertThrows(IllegalStateException.class, config::selectedModelTag);
        var environment = new MockEnvironment().withProperty("HARNESS_OLLAMA_MODEL", "model-1");
        assertEquals("model-1", new ModelConfiguration(environment).selectedModelTag());
        environment.setProperty("HARNESS_OLLAMA_MODEL", "model-1:cloud");
        assertThrows(IllegalArgumentException.class, () -> new ModelConfiguration(environment).selectedModelTag());
    }

    @Test void defaultModelSecretsDirectoryIsOutsideConfigtree() throws Exception {
        var properties = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
        var environment = new MockEnvironment();
        properties.forEach(source -> environment.getPropertySources().addLast(source));
        Path modelSecrets = Path.of(environment.resolvePlaceholders(
                environment.getRequiredProperty("harness.model.secrets-directory"))).normalize();
        String configtree = environment.resolvePlaceholders(environment.getRequiredProperty("spring.config.import"));
        Path appSecrets = Path.of(configtree.substring("optional:configtree:".length())).normalize();
        assertEquals(Path.of(".secrets/models"), modelSecrets);
        assertFalse(modelSecrets.startsWith(appSecrets));
    }
}
