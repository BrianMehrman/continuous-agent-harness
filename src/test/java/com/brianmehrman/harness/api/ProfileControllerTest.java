package com.brianmehrman.harness.api;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.model.OllamaProfile;
import org.junit.jupiter.api.Test;

class ProfileControllerTest {
    @Test void marksOnlyTheConfiguredDeveloperProfileAsBound() {
        var profile = new OllamaProfile("local-profile", "ollama", "http://127.0.0.1:11434",
                "fixture-local", "a".repeat(64), 8192, 1024, 0, true, null);
        assertTrue(ProfileController.ProfileView.from(profile, "local-profile").boundToDeveloper());
        assertFalse(ProfileController.ProfileView.from(profile, "another-profile").boundToDeveloper());
    }
}
