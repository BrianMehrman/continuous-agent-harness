package com.brianmehrman.harness.runs;

import com.brianmehrman.harness.execution.AgentRoleCatalog;
import com.brianmehrman.harness.execution.CostTerms;
import com.brianmehrman.harness.execution.RunTerms;
import com.brianmehrman.harness.model.ProfileRevision;
import com.brianmehrman.harness.model.ProfileRevisionStore;
import java.nio.charset.StandardCharsets;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class RunTermsResolver {
    private final AgentRoleCatalog roles;
    private final ProfileRevisionStore profiles;
    private final BlobStore blobs;
    private final Environment environment;

    public RunTermsResolver(AgentRoleCatalog roles, ProfileRevisionStore profiles, BlobStore blobs,
            Environment environment) {
        this.roles = roles;
        this.profiles = profiles;
        this.blobs = blobs;
        this.environment = environment;
    }

    public Resolved resolve(RunOptions options) {
        String name = options.role() == null ? "developer" : options.role();
        var role = roles.get(name);
        String profileId = options.profileRevision() == null
                ? environment.getProperty("harness.roles." + name + ".profile") : options.profileRevision();
        if (profileId == null || profileId.isBlank()) throw new IllegalArgumentException("ROLE_UNBOUND");
        ProfileRevision profile = profiles.get(profileId);
        if (profile.remote() && !options.allowRemote()) throw new IllegalArgumentException("REMOTE_NOT_ALLOWED");
        if (!profile.prices().free() && options.costLimitMicros() == null)
            throw new IllegalArgumentException("COST_LIMIT_REQUIRED");
        int buffer = options.costBufferPercent() == null ? 10 : options.costBufferPercent();
        if (buffer < 0 || buffer > 50) throw new IllegalArgumentException("INVALID_COST_BUFFER");
        if (options.costLimitMicros() != null && options.costLimitMicros() <= 0)
            throw new IllegalArgumentException("INVALID_COST_LIMIT");
        String digest = blobs.put("application/vnd.harness.role+json",
                role.canonicalJson().getBytes(StandardCharsets.UTF_8));
        var cost = new CostTerms(profile.prices(), profile.contextTokens() - profile.outputTokens(),
                profile.outputTokens(), options.costLimitMicros(), buffer);
        return new Resolved(profile, new RunTerms(role.name(), digest, digest, role.tools(),
                profile.modelName(), options.allowRemote(), cost));
    }

    public record Resolved(ProfileRevision profile, RunTerms terms) {}
}
