package com.brianmehrman.harness.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ModelConfigurationTest {
    @Test void liveSelectionRequiresExplicitModelAndDoesNotChooseDefault() {
        var config = new ModelConfiguration(new MockEnvironment());
        assertThrows(IllegalStateException.class, config::selectedModelTag);
        var environment = new MockEnvironment().withProperty("HARNESS_OLLAMA_MODEL", "model-1");
        assertEquals("model-1", new ModelConfiguration(environment).selectedModelTag());
        environment.setProperty("HARNESS_OLLAMA_MODEL", "model-1:cloud");
        assertThrows(IllegalArgumentException.class, () -> new ModelConfiguration(environment).selectedModelTag());
    }
}
