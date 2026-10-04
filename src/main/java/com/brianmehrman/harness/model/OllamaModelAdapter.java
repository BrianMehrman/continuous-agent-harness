package com.brianmehrman.harness.model;

import com.brianmehrman.harness.runs.BlobStore;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** A single, non-streaming local model request. Tool callbacks are definitions only. */
public final class OllamaModelAdapter implements ModelAdapter {
    private static final int MAX_CONVERSATION_BYTES = 4 * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final BlobStore blobs;
    private final ProfileRevision profile;
    private final LocalProfileProbe probe;
    private final List<ToolCallback> tools;

    public OllamaModelAdapter(BlobStore blobs, ProfileRevision profile) {
        this(blobs, profile, TOOLS);
    }

    OllamaModelAdapter(BlobStore blobs, ProfileRevision profile, List<ToolCallback> tools) {
        this.blobs = Objects.requireNonNull(blobs);
        this.profile = Objects.requireNonNull(profile);
        this.probe = new LocalProfileProbe(profile.endpoint());
        this.tools = List.copyOf(tools);
    }

    @Override public ModelReply call(ModelCall request) {
        if (request == null || !profile.id().equals(request.profileRevision()))
            throw new IllegalArgumentException("Profile revision mismatch");
        long remaining = request.deadlineEpochMillis() - System.currentTimeMillis();
        if (remaining <= 0) throw new IllegalStateException("MODEL_DEADLINE_EXCEEDED");
        byte[] bytes = blobs.get(request.conversationBlobId());
        if (bytes.length > MAX_CONVERSATION_BYTES) throw new IllegalArgumentException("MODEL_CONTEXT_OVERFLOW");
        List<Message> messages = messages(bytes);
        probe.verifyDigest(profile, request.deadlineEpochMillis());
        remaining = request.deadlineEpochMillis() - System.currentTimeMillis();
        if (remaining <= 0) throw new IllegalStateException("MODEL_DEADLINE_EXCEEDED");
        // Spring AI's low-level ChatModel returns native requests without a ChatClient tool loop.
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(remaining, 5000))).build());
        factory.setReadTimeout(Duration.ofMillis(remaining));
        var api = OllamaApi.builder().baseUrl(profile.endpoint())
                .restClientBuilder(RestClient.builder().requestFactory(factory)
                        .requestInterceptor((http, body, execution) -> {
                            ObjectNode chat = (ObjectNode) JSON.readTree(body);
                            chat.put("truncate", false);
                            chat.put("shift", false);
                            byte[] bounded = JSON.writeValueAsBytes(chat);
                            http.getHeaders().setContentLength(bounded.length);
                            return execution.execute(http, bounded);
                        })).build();
        var options = OllamaChatOptions.builder().model(profile.modelTag())
                .numCtx(profile.contextTokens()).numPredict(profile.outputTokens())
                .temperature(profile.temperature()).truncate(false).toolCallbacks(tools).build();
        var model = OllamaChatModel.builder().ollamaApi(api).options(options)
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0))).build();
        org.springframework.ai.chat.model.ChatResponse response;
        try {
            response = model.call(new Prompt(messages, options));
        } catch (ResourceAccessException e) {
            if (causedByTimeout(e)) throw new IllegalStateException("MODEL_DEADLINE_EXCEEDED", e);
            throw new IllegalStateException("MODEL_TRANSPORT", e);
        } catch (NonTransientAiException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("400 -") && contextOverflow(e.getMessage()))
                throw new IllegalArgumentException("MODEL_CONTEXT_OVERFLOW", e);
            throw new IllegalStateException("MODEL_TRANSPORT", e);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 400 && contextOverflow(e.getResponseBodyAsString()))
                throw new IllegalArgumentException("MODEL_CONTEXT_OVERFLOW", e);
            throw new IllegalStateException("MODEL_TRANSPORT", e);
        } catch (RestClientException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof HttpMessageNotReadableException)
                    throw new IllegalStateException("MODEL_MALFORMED_RESPONSE", e);
            }
            throw new IllegalStateException("MODEL_TRANSPORT", e);
        }
        if (response.getResult() == null) throw new IllegalStateException("MODEL_EMPTY_RESPONSE");
        var generation = response.getResult();
        var output = generation.getOutput();
        var tools = new ArrayList<ToolRequest>();
        var seen = new HashSet<String>();
        for (var tool : output.getToolCalls()) {
            String id = tool.id();
            if (id == null || id.isBlank()) id = request.invocationId() + ":" + tools.size();
            if (!seen.add(id)) throw new IllegalStateException("Duplicate provider tool call ID");
            tools.add(new ToolRequest(id, tool.name(), tool.arguments()));
        }
        var usage = response.getMetadata().getUsage();
        boolean reportedUsage = generation.getMetadata() != ChatGenerationMetadata.NULL;
        Long input = !reportedUsage || usage == null ? null : usage.getPromptTokens().longValue();
        Long outputTokens = !reportedUsage || usage == null ? null : usage.getCompletionTokens().longValue();
        return new ModelReply(output.getText(), tools, input, outputTokens,
                generation.getMetadata().getFinishReason());
    }

    private static boolean causedByTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException)
                return true;
        }
        return false;
    }

    private static boolean contextOverflow(String body) {
        String message = body.toLowerCase(java.util.Locale.ROOT);
        return (message.contains("context") && (message.contains("exceed") || message.contains("too long") ||
                message.contains("too large") || message.contains("cannot fit") ||
                message.contains("overflow") || message.contains("limit"))) ||
                (message.contains("prompt") && message.contains("too long"));
    }

    private static List<Message> messages(byte[] bytes) {
        JsonNode root = JSON.readTree(bytes);
        if (root == null || root.path("version").asInt() != 1 || !root.path("messages").isArray())
            throw new IllegalArgumentException("Invalid versioned conversation");
        var result = new ArrayList<Message>();
        Map<String, String> pending = new HashMap<>();
        for (JsonNode item : root.path("messages")) {
            String role = item.path("role").asText();
            String content = item.path("content").asText("");
            switch (role) {
                case "system" -> {
                    if (!pending.isEmpty()) throw new IllegalArgumentException("Unresolved tool calls");
                    result.add(new SystemMessage(content));
                }
                case "user" -> {
                    if (!pending.isEmpty()) throw new IllegalArgumentException("Unresolved tool calls");
                    result.add(new UserMessage(content));
                }
                case "assistant" -> {
                    if (!pending.isEmpty()) throw new IllegalArgumentException("Unresolved tool calls");
                    var calls = new ArrayList<AssistantMessage.ToolCall>();
                    if (item.has("toolCalls")) {
                        if (!item.path("toolCalls").isArray()) throw new IllegalArgumentException("Invalid tool calls");
                        for (JsonNode call : item.path("toolCalls")) {
                            String id = required(call, "id");
                            String name = required(call, "name");
                            if (pending.putIfAbsent(id, name) != null)
                                throw new IllegalArgumentException("Duplicate conversation tool call ID");
                            calls.add(new AssistantMessage.ToolCall(id, "function", name,
                                    required(call, "argumentsJson")));
                        }
                    }
                    result.add(AssistantMessage.builder().content(content).toolCalls(calls).build());
                }
                case "tool" -> {
                    String id = required(item, "id");
                    String name = required(item, "name");
                    if (!name.equals(pending.remove(id)))
                        throw new IllegalArgumentException("Tool result does not match a pending call");
                    result.add(ToolResponseMessage.builder().responses(List.of(
                            new ToolResponseMessage.ToolResponse(id, name, content))).build());
                }
                default -> throw new IllegalArgumentException("Unknown conversation role");
            }
        }
        if (!pending.isEmpty()) throw new IllegalArgumentException("Unresolved tool calls");
        if (result.isEmpty()) throw new IllegalArgumentException("Empty conversation");
        return result;
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing conversation " + field);
        return value;
    }

    private static final List<ToolCallback> TOOLS = List.of(
            tool("list_files", "List paths in the current immutable snapshot", "{\"type\":\"object\",\"properties\":{\"prefix\":{\"type\":\"string\"}}}"),
            tool("read_file", "Read a file in the current immutable snapshot", "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"),
            tool("write_file", "Replace a file after checking its expected hash", "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"expectedSha256\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"expectedSha256\",\"content\"]}"),
            tool("run_tests", "Run the fixed test command on the current snapshot", "{\"type\":\"object\",\"properties\":{}}"),
            tool("build", "Build the current snapshot with the fixed command", "{\"type\":\"object\",\"properties\":{}}"),
            tool("submit", "Submit the current snapshot for independent evaluation", "{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"}},\"required\":[\"summary\"]}"));

    private static ToolCallback tool(String name, String description, String schema) {
        return new ToolCallback() {
            private final ToolDefinition definition = ToolDefinition.builder().name(name)
                    .description(description).inputSchema(schema).build();
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public String call(String input) { throw new IllegalStateException("Tools are executed only by the harness"); }
        };
    }
}
