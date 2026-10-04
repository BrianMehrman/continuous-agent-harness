package com.brianmehrman.harness.model;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.ollama.api.OllamaApi;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class LocalProfileProbe {
    private final OllamaApi api;
    private final URI endpoint;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    public LocalProfileProbe(String endpoint) {
        this.endpoint = URI.create(endpoint);
        if (!"http".equals(this.endpoint.getScheme()) || this.endpoint.getHost() == null ||
                !(this.endpoint.getHost().equals("localhost") || this.endpoint.getHost().equals("127.0.0.1") || this.endpoint.getHost().equals("::1")))
            throw new IllegalArgumentException("Ollama endpoint must use loopback HTTP");
        this.api = OllamaApi.builder().baseUrl(Objects.requireNonNull(endpoint)).build();
    }

    public ProfileRevision probe(String tag, int contextTokens, int outputTokens, double temperature) {
        if (tag == null || tag.isBlank() || tag.endsWith(":cloud"))
            throw new IllegalArgumentException("An explicitly selected local model is required");
        var models = api.listModels();
        var model = models.models() == null ? null : models.models().stream()
                .filter(candidate -> tag.equals(candidate.name())).findFirst().orElse(null);
        if (model == null || model.digest() == null || !model.digest().matches("(?:sha256:)?[0-9a-f]{64}") ||
                model.size() == null || model.size() <= 0 || model.details() == null ||
                !"gguf".equalsIgnoreCase(model.details().format()))
            throw new IllegalStateException("LOCAL_WEIGHTS_UNVERIFIED");
        var shown = api.showModel(new OllamaApi.ShowModelRequest(tag));
        if (shown.details() == null || !"gguf".equalsIgnoreCase(shown.details().format()) ||
                shown.capabilities() == null || !shown.capabilities().contains("tools") ||
                shown.modelInfo() == null || shown.modelInfo().isEmpty() ||
                shown.modelInfo().toString().toLowerCase().contains("remote") ||
                shown.modelfile() == null || shown.modelfile().toLowerCase().contains(":cloud") ||
                !java.util.regex.Pattern.compile("(?m)^[ \\t]*FROM[ \\t]+/.*[/\\\\]blobs[/\\\\]sha256[-:][0-9a-f]{64}[ \\t]*$")
                        .matcher(shown.modelfile()).find())
            throw new IllegalStateException("LOCAL_TOOL_MODEL_UNVERIFIED");
        readOnlyToolRoundTrip(tag, contextTokens, outputTokens, temperature);
        String id = "ollama-" + sha256(endpoint + "\n" + tag + "\n" + model.digest() + "\n" +
                contextTokens + "\n" + outputTokens + "\n" + temperature);
        var profile = new ProfileRevision(id, "ollama", endpoint.toString(), tag, model.digest(),
                contextTokens, outputTokens, temperature, true, null);
        verifyDigest(profile);
        return profile;
    }

    public void verifyDigest(ProfileRevision profile) {
        verifyDigest(profile, System.currentTimeMillis() + 10_000);
    }

    public void verifyDigest(ProfileRevision profile, long deadlineEpochMillis) {
        long remaining = deadlineEpochMillis - System.currentTimeMillis();
        if (remaining <= 0) throw new IllegalStateException("MODEL_DEADLINE_EXCEEDED");
        try {
            var request = HttpRequest.newBuilder(endpoint.resolve("/api/tags"))
                    .timeout(Duration.ofMillis(remaining)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("MODEL_TRANSPORT");
            JsonNode models = json.readTree(response.body()).path("models");
            if (!models.isArray()) throw new IllegalStateException("PROFILE_DRIFT");
            for (JsonNode model : models) {
                if (profile.modelTag().equals(model.path("name").asText()) &&
                        profile.fullDigest().equals(model.path("digest").asText())) return;
            }
            throw new IllegalStateException("PROFILE_DRIFT");
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IllegalStateException("MODEL_DEADLINE_EXCEEDED", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MODEL_INTERRUPTED", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("MODEL_TRANSPORT", e);
        }
    }

    private void readOnlyToolRoundTrip(String tag, int context, int output, double temperature) {
        var options = Map.of("num_ctx", context, "num_predict", output, "temperature", temperature);
        var user = Map.of("role", "user", "content",
                "Call list_files with no prefix. Then repeat the marker from its result exactly.");
        var tool = Map.of("type", "function", "function", Map.of("name", "list_files",
                "description", "List paths in a snapshot", "parameters", Map.of("type", "object", "properties", Map.of())));
        JsonNode first = post(Map.of("model", tag, "stream", false, "messages", List.of(user),
                "tools", List.of(tool), "options", options));
        JsonNode calls = first.path("message").path("tool_calls");
        if (!calls.isArray() || calls.size() != 1 || !"list_files".equals(calls.get(0).path("function").path("name").asText()))
            throw new IllegalStateException("MODEL_TOOL_PROBE_FAILED");
        var assistant = Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of(
                "function", Map.of("name", "list_files", "arguments", Map.of()))));
        String marker = "HARNESS_PROBE_" + java.util.UUID.randomUUID().toString().replace("-", "");
        var result = Map.of("role", "tool", "content", json.writeValueAsString(Map.of("marker", marker, "paths", List.of())));
        JsonNode second = post(Map.of("model", tag, "stream", false, "messages", List.of(user, assistant, result),
                "options", options));
        JsonNode extraCalls = second.path("message").path("tool_calls");
        if (!second.path("message").path("content").asText().contains(marker) ||
                !(extraCalls.isMissingNode() || extraCalls.isNull() || (extraCalls.isArray() && extraCalls.isEmpty())))
            throw new IllegalStateException("MODEL_TOOL_ROUND_TRIP_FAILED");
    }

    private JsonNode post(Object request) {
        try {
            var http = HttpRequest.newBuilder(endpoint.resolve("/api/chat"))
                    .timeout(Duration.ofSeconds(180)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build();
            var response = client.send(http, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("MODEL_TOOL_PROBE_HTTP_" + response.statusCode());
            return json.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MODEL_TOOL_PROBE_INTERRUPTED", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("MODEL_TOOL_PROBE_TRANSPORT", e);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
