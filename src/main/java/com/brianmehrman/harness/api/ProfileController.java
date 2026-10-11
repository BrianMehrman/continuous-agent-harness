package com.brianmehrman.harness.api;

import com.brianmehrman.harness.config.ModelConfiguration;
import com.brianmehrman.harness.model.ProfileRevision;
import com.brianmehrman.harness.model.ProfileRevisionStore;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("api")
public class ProfileController {
    private final JdbcTemplate jdbc;
    private final ProfileRevisionStore profiles;
    private final ModelConfiguration models;

    public ProfileController(JdbcTemplate jdbc, ProfileRevisionStore profiles, ModelConfiguration models) {
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.models = models;
    }

    @GetMapping("/api/profiles") public List<ProfileView> list() {
        return jdbc.queryForList("select id from model_profile_revision order by created_at desc,id desc limit 100",
                String.class).stream().map(profiles::get).map(ProfileView::from).toList();
    }

    @PostMapping(value = "/api/profiles/probe", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ProfileView probe(@RequestBody String body) {
        Map<String, Object> data = RunController.object(body, Set.of("modelTag"));
        String tag = RunController.string(data, "modelTag");
        try { return ProfileView.from(models.selectLocalProfile(profiles, tag)); }
        catch (IllegalArgumentException invalid) { throw new RunController.BadInput(invalid.getMessage()); }
        catch (IllegalStateException failed) {
            if (Set.of("LOCAL_WEIGHTS_UNVERIFIED", "LOCAL_TOOL_MODEL_UNVERIFIED", "PROFILE_DRIFT")
                    .contains(failed.getMessage()))
                throw new RunController.BadInput(failed.getMessage());
            throw new ApiErrors.ServiceUnavailable("Local model probe unavailable");
        }
    }

    public record ProfileView(String id, String adapter, String endpoint, String modelTag,
            String fullDigest, int contextTokens, int outputTokens, double temperature,
            boolean toolRoundTripVerified) {
        static ProfileView from(ProfileRevision profile) {
            return new ProfileView(profile.id(), profile.adapter(), profile.endpoint(), profile.modelTag(),
                    profile.fullDigest(), profile.contextTokens(), profile.outputTokens(),
                    profile.temperature(), profile.toolRoundTripVerified());
        }
    }
}
