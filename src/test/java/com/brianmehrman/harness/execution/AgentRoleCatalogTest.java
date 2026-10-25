package com.brianmehrman.harness.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

class AgentRoleCatalogTest {
    @TempDir Path temp;

    @Test void loadsDeveloperWithAllSixTools() {
        AgentRole role = AgentRoleCatalog.load(new DefaultResourceLoader()).get("developer");
        assertEquals("developer", role.name());
        assertEquals(1, role.version());
        assertEquals(List.of("list_files", "read_file", "write_file", "run_tests", "build", "submit"), role.tools());
        assertTrue(role.prompt().startsWith("You implement one focused change"));
        assertEquals("ROLE_UNKNOWN", assertThrows(IllegalArgumentException.class,
                () -> AgentRoleCatalog.load(new DefaultResourceLoader()).get("unknown")).getMessage());
    }

    @Test void rejectsUnknownToolDuplicateAndMismatchedName() throws Exception {
        Path roles = Files.createDirectory(temp.resolve("roles"));
        try (var loader = new URLClassLoader(new java.net.URL[]{temp.toUri().toURL()}, null)) {
            var resources = new DefaultResourceLoader(loader);
            Path first = roles.resolve("developer.yaml");
            Files.writeString(first, "name: developer\nversion: 1\nprompt: Do work\ntools: [unknown]\n");
            assertThrows(IllegalArgumentException.class, () -> AgentRoleCatalog.load(resources));
            Files.writeString(first, "name: other\nversion: 1\nprompt: Do work\ntools: [read_file]\n");
            assertThrows(IllegalArgumentException.class, () -> AgentRoleCatalog.load(resources));
            Files.writeString(first, "name: developer\nversion: 1\nprompt: Do work\ntools: [read_file, read_file]\n");
            assertThrows(IllegalArgumentException.class, () -> AgentRoleCatalog.load(resources));
        }
    }

    @Test void canonicalJsonIsStable() {
        var role = new AgentRole("developer", 1, "Do work", List.of("read_file", "list_files"));
        var reordered = new AgentRole("developer", 1, "Do work", List.of("list_files", "read_file"));
        assertEquals("{\"name\":\"developer\",\"version\":1,\"prompt\":\"Do work\",\"tools\":[\"list_files\",\"read_file\"]}",
                role.canonicalJson());
        assertEquals(role.canonicalJson(), reordered.canonicalJson());
    }
}
