package com.brianmehrman.harness.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "adapter", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = OllamaProfile.class, name = "ollama"),
        @JsonSubTypes.Type(value = AnthropicProfile.class, name = "anthropic")
})
public sealed interface ProfileRevision permits OllamaProfile, AnthropicProfile {
    String id();
    String adapter();
    String modelName();
    int contextTokens();
    int outputTokens();
    Prices prices();
    boolean remote();
    boolean toolRoundTripVerified();
}
