package com.brianmehrman.harness.config;

import com.brianmehrman.harness.model.LocalProfileProbe;
import com.brianmehrman.harness.model.ProfileRevision;
import com.brianmehrman.harness.model.ProfileRevisionStore;
import com.brianmehrman.harness.execution.ModelAdapterFactory;
import com.brianmehrman.harness.model.OllamaModelAdapter;
import com.brianmehrman.harness.runs.BlobStore;
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

    public ProfileRevision selectLocalProfile(ProfileRevisionStore store) {
        String endpoint = environment.getProperty("harness.model.ollama.endpoint", "http://127.0.0.1:11434");
        int context = environment.getProperty("harness.model.context-tokens", Integer.class, 8192);
        int output = environment.getProperty("harness.model.output-tokens", Integer.class, 1024);
        double temperature = environment.getProperty("harness.model.temperature", Double.class, 0.0);
        return store.save(new LocalProfileProbe(endpoint).probe(selectedModelTag(), context, output, temperature));
    }

    @Bean
    @Profile("worker")
    ModelAdapterFactory modelAdapterFactory(BlobStore blobs) {
        return profile -> new OllamaModelAdapter(blobs, profile);
    }
}
