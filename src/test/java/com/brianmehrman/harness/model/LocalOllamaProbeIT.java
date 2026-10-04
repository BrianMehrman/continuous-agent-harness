package com.brianmehrman.harness.model;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.config.ModelConfiguration;
import com.brianmehrman.harness.runs.BlobStore;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Opt-in local capability check; never downloads a model or runs the coding benchmark. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("runner")
class LocalOllamaProbeIT {
    @Autowired ModelConfiguration selection;
    @Autowired ProfileRevisionStore profiles;
    @Autowired BlobStore blobs;

    @Test void selectedInstalledModelCompletesReadOnlyNativeToolRoundTrip() {
        String tag = System.getenv("HARNESS_OLLAMA_MODEL");
        Assumptions.assumeTrue(tag != null && !tag.isBlank(), "Set HARNESS_OLLAMA_MODEL to an installed local tag");
        ProfileRevision profile = selection.selectLocalProfile(profiles);
        assertEquals(tag, profile.modelTag());
        assertTrue(profile.fullDigest().matches("(?:sha256:)?[0-9a-f]{64}"));
        assertTrue(profile.toolRoundTripVerified());
        assertEquals(profile, profiles.get(profile.id()));
        String conversation = blobs.put("application/vnd.harness.conversation+json",
                "{\"version\":1,\"messages\":[{\"role\":\"user\",\"content\":\"Reply with OK.\"}]}"
                        .getBytes(StandardCharsets.UTF_8));
        ModelReply reply = new OllamaModelAdapter(blobs, profile).call(new ModelCall(
                "task5-probe", "task5-probe-turn", 1, profile.id(), conversation,
                System.currentTimeMillis() + 120_000));
        assertTrue(!reply.text().isBlank() || !reply.tools().isEmpty());
    }
}
