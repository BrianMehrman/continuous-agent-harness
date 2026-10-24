package com.brianmehrman.harness.runs;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.execution.AgentRoleCatalog;
import com.brianmehrman.harness.model.AnthropicProfile;
import com.brianmehrman.harness.model.OllamaProfile;
import com.brianmehrman.harness.model.Prices;
import com.brianmehrman.harness.model.ProfileRevision;
import com.brianmehrman.harness.model.ProfileRevisionStore;
import com.brianmehrman.harness.workspace.SnapshotHasher;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;

class RunTermsResolverTest {
    private final Map<String, ProfileRevision> profiles = new HashMap<>();
    private final ProfileRevisionStore store = new ProfileRevisionStore() {
        @Override public ProfileRevision save(ProfileRevision profile) { profiles.put(profile.id(), profile); return profile; }
        @Override public ProfileRevision get(String id) { return profiles.get(id); }
    };
    private final Map<String, byte[]> saved = new HashMap<>();
    private final BlobStore blobs = new BlobStore() {
        @Override public String put(String type, byte[] bytes) {
            String digest = SnapshotHasher.content(new String(bytes, StandardCharsets.UTF_8));
            saved.put(digest, bytes.clone());
            return digest;
        }
        @Override public byte[] get(String digest) { return saved.get(digest).clone(); }
    };
    private final MockEnvironment environment = new MockEnvironment();

    private RunTermsResolver resolver() {
        return new RunTermsResolver(AgentRoleCatalog.load(new DefaultResourceLoader()), store, blobs, environment);
    }

    @Test void unboundRoleWithoutProfileIsRejected() {
        assertEquals("ROLE_UNBOUND", assertThrows(IllegalArgumentException.class,
                () -> resolver().resolve(new RunOptions(null, null, false, null, null))).getMessage());
    }

    @Test void remoteProfileRequiresOptInAndCostLimit() {
        store.save(new AnthropicProfile("remote", "anthropic", "https://api.anthropic.com", "claude-haiku-5-5",
                "anthropic_api_key", 16384, 4096, 100000,
                new Prices(100000, 500000, 125000, 10000), true));
        assertEquals("REMOTE_NOT_ALLOWED", assertThrows(IllegalArgumentException.class,
                () -> resolver().resolve(new RunOptions(null, "remote", false, 100000L, null))).getMessage());
        assertEquals("COST_LIMIT_REQUIRED", assertThrows(IllegalArgumentException.class,
                () -> resolver().resolve(new RunOptions(null, "remote", true, null, null))).getMessage());
        assertEquals("INVALID_COST_BUFFER", assertThrows(IllegalArgumentException.class,
                () -> resolver().resolve(new RunOptions(null, "remote", true, 100000L, 60))).getMessage());
    }

    @Test void freeProfileUsesLocalBindingAndFrozenRoleWithoutLimit() {
        store.save(new OllamaProfile("local", "ollama", "http://127.0.0.1:11434", "local-model",
                "sha256:" + "a".repeat(64), 16384, 4096, 0.0, true, null));
        environment.setProperty("harness.roles.developer.profile", "local");
        var resolved = resolver().resolve(new RunOptions(null, null, false, null, null));
        assertEquals("local", resolved.profile().id());
        assertEquals("developer", resolved.terms().roleName());
        assertEquals("local-model", resolved.terms().modelName());
        assertEquals(12288, resolved.terms().cost().worstCaseInputTokens());
        assertNull(resolved.terms().cost().limitMicros());
        assertArrayEquals(saved.get(resolved.terms().roleDigest()), blobs.get(resolved.terms().roleBlobId()));
    }
}
