package com.brianmehrman.harness.config;

import com.brianmehrman.harness.model.LocalProfileProbe;
import com.brianmehrman.harness.model.ProfileRevisionStore;
import com.brianmehrman.harness.execution.ModelAdapterFactory;
import com.brianmehrman.harness.model.OllamaModelAdapter;
import com.brianmehrman.harness.model.OllamaProfile;
import com.brianmehrman.harness.model.AnthropicProfile;
import com.brianmehrman.harness.model.AnthropicModelAdapter;
import com.brianmehrman.harness.model.CredentialFiles;
import com.brianmehrman.harness.model.AnthropicProfileProbe;
import com.brianmehrman.harness.model.Prices;
import com.brianmehrman.harness.runs.BlobStore;
import java.nio.file.Path;
import java.util.Objects;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

/** Explicit operator selection; constructing the application never probes or pulls a model. */
@Component
public final class ModelConfiguration {
    private final Environment environment;

    public ModelConfiguration(Environment environment) { this.environment = Objects.requireNonNull(environment); }

    public String selectedModelTag() {
        String tag = environment.getProperty("HARNESS_OLLAMA_MODEL");
        if (tag == null || tag.isBlank()) throw new IllegalStateException("HARNESS_OLLAMA_MODEL is required for live setup");
        if (tag.endsWith(":cloud")) throw new IllegalArgumentException("Cloud-backed models are not supported");
        return tag;
    }

    public OllamaProfile selectLocalProfile(ProfileRevisionStore store) {
        return selectLocalProfile(store, selectedModelTag());
    }

    public OllamaProfile selectLocalProfile(ProfileRevisionStore store, String modelTag) {
        String endpoint = environment.getProperty("harness.model.ollama.endpoint", "http://127.0.0.1:11434");
        int context = environment.getProperty("harness.model.context-tokens", Integer.class, 16384);
        int output = environment.getProperty("harness.model.output-tokens", Integer.class, 4096);
        double temperature = environment.getProperty("harness.model.temperature", Double.class, 0.0);
        return (OllamaProfile) store.save(new LocalProfileProbe(endpoint).probe(modelTag, context, output, temperature));
    }

    public AnthropicProfile selectAnthropicProfile(ProfileRevisionStore store, String modelId,
            String credentialReference, int contextTokens, int outputTokens,
            int priceTierInputTokens, Prices prices) {
        var credentials = new CredentialFiles(Path.of(environment.getProperty(
                "harness.model.secrets-directory", "./.secrets/models/")));
        return (AnthropicProfile) store.save(new AnthropicProfileProbe(credentials).probe(modelId,
                credentialReference, contextTokens, outputTokens, priceTierInputTokens, prices));
    }

    @Bean
    @Profile("worker")
    ModelAdapterFactory modelAdapterFactory(BlobStore blobs) {
        var credentials = new CredentialFiles(Path.of(environment.getProperty(
                "harness.model.secrets-directory", "./.secrets/models/")));
        return profile -> switch (profile) {
            case OllamaProfile ollama -> new OllamaModelAdapter(blobs, ollama);
            case AnthropicProfile anthropic -> new AnthropicModelAdapter(blobs, anthropic, credentials);
        };
    }
}
