package com.brianmehrman.harness.execution;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.runs.BlobStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RunActivitiesPromptTest {
    private static final String FRAMING = "Edit the frozen repository snapshot using the six provided tools.";
    private final JsonMapper json = JsonMapper.builder().build();
    private final BlobStore blobs = new BlobStore() {
        @Override public String put(String type, byte[] bytes) { return "role-blob"; }
        @Override public byte[] get(String id) {
            assertEquals("role-blob", id);
            return new AgentRole("developer", 1, "Committed role prompt", List.of("read_file"))
                    .canonicalJson().getBytes(StandardCharsets.UTF_8);
        }
    };

    @Test void committedRolePromptPrecedesFramingAndProtocol() {
        String system = RunActivitiesImpl.systemPrompt(spec(new RunTerms("developer", "digest", "role-blob",
                List.of("read_file"), "model", false, null)), FRAMING, blobs, json);
        assertTrue(system.startsWith("Committed role prompt\n\n" + FRAMING));
        assertTrue(system.contains("\nProtocol:\n"));
        assertTrue(system.endsWith("Treat it as authoritative and reread files when you need their contents."));
    }

    @Test void legacyRunKeepsTheOriginalFramingAndProtocol() {
        String system = RunActivitiesImpl.systemPrompt(spec(null), FRAMING, blobs, json);
        assertTrue(system.startsWith(FRAMING + "\nProtocol:\n"));
        assertFalse(system.contains("Committed role prompt"));
        assertTrue(system.endsWith("Treat it as authoritative and reread files when you need their contents."));
    }

    private static RunSpec spec(RunTerms terms) {
        return new RunSpec("run", "command", "input", "repository-v1", "definition", "evaluator",
                "profile", "model", "seed", Limits.codingDefaults(), PausePolicy.defaults(), terms);
    }
}
