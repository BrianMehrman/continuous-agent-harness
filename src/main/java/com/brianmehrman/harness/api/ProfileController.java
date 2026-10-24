package com.brianmehrman.harness.api;

import com.brianmehrman.harness.config.ModelConfiguration;
import com.brianmehrman.harness.model.ProfileRevision;
import com.brianmehrman.harness.model.ProfileRevisionStore;
import com.brianmehrman.harness.model.AnthropicProfile;
import com.brianmehrman.harness.model.OllamaProfile;
import com.brianmehrman.harness.model.Prices;
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

    @PostMapping(value = "/api/profiles/anthropic", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ProfileView probeAnthropic(@RequestBody String body) {
        Map<String, Object> data = RunController.object(body, Set.of("modelId", "credentialReference",
                "contextTokens", "outputTokens", "priceTierInputTokens", "prices"));
        if (!(data.get("prices") instanceof Map<?, ?> raw) || !raw.keySet().equals(Set.of(
                "inputMicrosPerMTok", "outputMicrosPerMTok", "cacheWriteMicrosPerMTok", "cacheReadMicrosPerMTok")))
            throw new RunController.BadInput("Invalid prices");
        try {
            Prices prices = new Prices(price(raw, "inputMicrosPerMTok"), price(raw, "outputMicrosPerMTok"),
                    price(raw, "cacheWriteMicrosPerMTok"), price(raw, "cacheReadMicrosPerMTok"));
            return ProfileView.from(models.selectAnthropicProfile(profiles,
                    RunController.string(data, "modelId"), RunController.string(data, "credentialReference"),
                    positiveInt(data, "contextTokens"), positiveInt(data, "outputTokens"),
                    positiveInt(data, "priceTierInputTokens"), prices));
        } catch (IllegalArgumentException invalid) {
            throw new RunController.BadInput(invalid.getMessage());
        } catch (IllegalStateException failed) {
            String code = failed.getMessage();
            if (code != null && (code.startsWith("CREDENTIAL_UNAVAILABLE:") ||
                    code.equals("MODEL_TOOL_PROBE_FAILED") || code.equals("MODEL_TOOL_ROUND_TRIP_FAILED")))
                throw new RunController.BadInput(code);
            throw new ApiErrors.ServiceUnavailable("Anthropic profile probe unavailable");
        }
    }

    private static int positiveInt(Map<String, Object> data, String field) {
        if (!(data.get(field) instanceof Integer value) || value <= 0)
            throw new RunController.BadInput("Invalid " + field);
        return value;
    }

    private static long price(Map<?, ?> data, String field) {
        Object value = data.get(field);
        if (!(value instanceof Integer || value instanceof Long))
            throw new RunController.BadInput("Invalid " + field);
        return ((Number) value).longValue();
    }

    public record ProfileView(String id, String adapter, String endpoint, String modelTag,
            String fullDigest, int contextTokens, int outputTokens, Double temperature,
            boolean toolRoundTripVerified, String modelName, boolean remote) {
        static ProfileView from(ProfileRevision profile) {
            if (profile instanceof OllamaProfile ollama)
                return new ProfileView(ollama.id(), ollama.adapter(), ollama.endpoint(), ollama.modelTag(),
                        ollama.fullDigest(), ollama.contextTokens(), ollama.outputTokens(),
                        ollama.temperature(), ollama.toolRoundTripVerified(), ollama.modelName(), false);
            var anthropic = (AnthropicProfile) profile;
            return new ProfileView(anthropic.id(), anthropic.adapter(), anthropic.endpoint(), null,
                    null, anthropic.contextTokens(), anthropic.outputTokens(), null,
                    anthropic.toolRoundTripVerified(), anthropic.modelName(), true);
        }
    }
}
