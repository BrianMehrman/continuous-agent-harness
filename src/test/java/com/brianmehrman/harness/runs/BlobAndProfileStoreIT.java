package com.brianmehrman.harness.runs;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.model.ProfileRevision;
import com.brianmehrman.harness.model.ProfileRevisionStore;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("runner")
class BlobAndProfileStoreIT {
    @Autowired BlobStore blobs;
    @Autowired ProfileRevisionStore profiles;

    @Test void blobsAreContentAddressedAndReadDefensively() {
        byte[] source = ("conversation-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String id = blobs.put("application/vnd.harness.conversation+json", source);
        source[0] = 'X';
        assertEquals(id, blobs.put("application/vnd.harness.conversation+json", blobs.get(id)));
        byte[] read = blobs.get(id);
        read[0] = 'Y';
        assertNotEquals('Y', blobs.get(id)[0]);
        assertThrows(IllegalArgumentException.class, () -> blobs.get("not-a-digest"));
        assertThrows(IllegalArgumentException.class, () -> blobs.put("text/plain", new byte[16_777_217]));
    }

    @Test void profileRevisionCannotBeReplacedWithDifferentSettings() {
        String id = "profile-it-" + UUID.randomUUID();
        var profile = new ProfileRevision(id, "ollama", "http://127.0.0.1:11434", "model-1",
                "a".repeat(64), 4096, 512, 0, true, null);
        assertEquals(profile, profiles.save(profile));
        assertEquals(profile, profiles.get(id));
        assertEquals(profile, profiles.save(profile));
        var changed = new ProfileRevision(id, "ollama", profile.endpoint(), profile.modelTag(),
                profile.fullDigest(), 8192, 512, 0, true, null);
        assertThrows(IllegalArgumentException.class, () -> profiles.save(changed));
        assertEquals(profile, profiles.get(id));
    }
}
