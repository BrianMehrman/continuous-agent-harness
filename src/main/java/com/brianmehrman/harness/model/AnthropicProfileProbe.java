package com.brianmehrman.harness.model;

import com.brianmehrman.harness.runs.BlobStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Verifies an Anthropic model's tool round trip without persisting a run or conversation. */
public final class AnthropicProfileProbe {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final CredentialFiles credentials;
    private final String baseUrl;

    public AnthropicProfileProbe(CredentialFiles credentials) {
        this(credentials, "https://api.anthropic.com");
    }

    AnthropicProfileProbe(CredentialFiles credentials, String baseUrl) {
        this.credentials = Objects.requireNonNull(credentials);
        this.baseUrl = Objects.requireNonNull(baseUrl);
    }

    public AnthropicProfile probe(String modelId, String credentialReference, int contextTokens,
            int outputTokens, int priceTierInputTokens, Prices prices) {
        String endpoint = "https://api.anthropic.com";
        String identity = String.join("\n", endpoint, modelId, credentialReference,
                Integer.toString(contextTokens), Integer.toString(outputTokens),
                Integer.toString(priceTierInputTokens), Long.toString(prices.inputMicrosPerMTok()),
                Long.toString(prices.outputMicrosPerMTok()), Long.toString(prices.cacheWriteMicrosPerMTok()),
                Long.toString(prices.cacheReadMicrosPerMTok()));
        String id = "anthropic-" + sha256(identity);
        var profile = new AnthropicProfile(id, "anthropic", endpoint, modelId, credentialReference,
                contextTokens, outputTokens, priceTierInputTokens, prices, true);
        var blobs = new MemoryBlobs();
        var adapter = new AnthropicModelAdapter(blobs, profile, credentials, baseUrl);
        String instruction = "Call list_files with no prefix. Then repeat the marker from its result exactly.";
        var user = Map.of("role", "user", "content", instruction);
        String firstConversation = blobs.put("application/vnd.harness.conversation+json",
                JSON.writeValueAsBytes(Map.of("version", 1, "messages", List.of(
                        Map.of("role", "system", "content", "Verify read-only tool use."), user))));
        long deadline = System.currentTimeMillis() + 180_000;
        ModelReply first = adapter.call(new ModelCall(id, "probe-1", 1, id, firstConversation,
                deadline, List.of("list_files")));
        var arguments = first.tools().size() == 1 ?
                JSON.readTree(first.tools().getFirst().argumentsJson()) : null;
        if (first.tools().size() != 1 || !"list_files".equals(first.tools().getFirst().name()) ||
                arguments == null || !arguments.isObject() || arguments.size() != 0)
            throw new IllegalStateException("MODEL_TOOL_PROBE_FAILED");
        ToolRequest request = first.tools().getFirst();
        String marker = "HARNESS_PROBE_" + UUID.randomUUID().toString().replace("-", "");
        var assistant = Map.of("role", "assistant", "content", first.text(), "toolCalls", List.of(
                Map.of("id", request.providerCallId(), "name", request.name(),
                        "argumentsJson", request.argumentsJson())));
        var result = Map.of("role", "tool", "id", request.providerCallId(), "name", request.name(),
                "content", JSON.writeValueAsString(Map.of("marker", marker, "paths", List.of())));
        String secondConversation = blobs.put("application/vnd.harness.conversation+json",
                JSON.writeValueAsBytes(Map.of("version", 1, "messages", List.of(
                        Map.of("role", "system", "content", "Verify read-only tool use."),
                        user, assistant, result))));
        ModelReply second = adapter.call(new ModelCall(id, "probe-2", 1, id, secondConversation,
                deadline, List.of()));
        if (!second.text().contains(marker) || !second.tools().isEmpty())
            throw new IllegalStateException("MODEL_TOOL_ROUND_TRIP_FAILED");
        return profile;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static final class MemoryBlobs implements BlobStore {
        private final Map<String, byte[]> content = new HashMap<>();

        @Override public String put(String mediaType, byte[] bytes) {
            String id = "probe-blob-" + content.size();
            content.put(id, bytes.clone());
            return id;
        }

        @Override public byte[] get(String digest) { return content.get(digest).clone(); }
    }
}
