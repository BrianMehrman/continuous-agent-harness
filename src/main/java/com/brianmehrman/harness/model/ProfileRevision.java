package com.brianmehrman.harness.model;

import java.net.URI;
import java.util.Objects;

public record ProfileRevision(String id, String adapter, String endpoint, String modelTag,
        String fullDigest, int contextTokens, int outputTokens, double temperature,
        boolean toolRoundTripVerified, String credentialReference) {
    public ProfileRevision {
        Objects.requireNonNull(id);
        if (id.isBlank()) throw new IllegalArgumentException("Profile ID is required");
        if (!"ollama".equals(adapter)) throw new IllegalArgumentException("Only the local Ollama adapter is supported");
        URI uri = URI.create(endpoint);
        if (!"http".equals(uri.getScheme()) || uri.getHost() == null ||
                !(uri.getHost().equals("localhost") || uri.getHost().equals("127.0.0.1") || uri.getHost().equals("::1")) ||
                uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Ollama endpoint must use loopback HTTP");
        }
        if (modelTag == null || modelTag.isBlank() || modelTag.endsWith(":cloud"))
            throw new IllegalArgumentException("A selected local model tag is required");
        if (fullDigest == null || !fullDigest.matches("(?:sha256:)?[0-9a-f]{64}"))
            throw new IllegalArgumentException("Full installed model digest is required");
        if (contextTokens <= 0 || outputTokens <= 0 || outputTokens >= contextTokens)
            throw new IllegalArgumentException("Invalid context or output limit");
        if (!Double.isFinite(temperature) || temperature < 0) throw new IllegalArgumentException("Invalid temperature");
        if (!toolRoundTripVerified) throw new IllegalArgumentException("A live tool round trip is required");
    }
}
