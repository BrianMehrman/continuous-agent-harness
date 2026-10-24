package com.brianmehrman.harness.model;

import java.util.Objects;

public record AnthropicProfile(String id, String adapter, String endpoint, String modelId,
        String credentialReference, int contextTokens, int outputTokens, int priceTierInputTokens,
        Prices prices, boolean toolRoundTripVerified) implements ProfileRevision {
    public AnthropicProfile {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Profile ID is required");
        if (!"anthropic".equals(adapter)) throw new IllegalArgumentException("Anthropic adapter is required");
        if (!"https://api.anthropic.com".equals(endpoint))
            throw new IllegalArgumentException("Anthropic endpoint must be https://api.anthropic.com");
        if (modelId == null || modelId.isBlank()) throw new IllegalArgumentException("Anthropic model ID is required");
        if (credentialReference == null || !credentialReference.matches("[a-z0-9_]{1,64}"))
            throw new IllegalArgumentException("Invalid credential reference");
        if (contextTokens <= 0 || outputTokens <= 0 || outputTokens >= contextTokens ||
                priceTierInputTokens <= 0 || contextTokens > priceTierInputTokens)
            throw new IllegalArgumentException("Invalid context, output or price tier limit");
        Objects.requireNonNull(prices, "Prices are required");
        if (prices.free()) throw new IllegalArgumentException("Anthropic profile requires prices");
        if (!toolRoundTripVerified) throw new IllegalArgumentException("A live tool round trip is required");
    }

    @Override public String modelName() { return modelId; }
    @Override public boolean remote() { return true; }
}
