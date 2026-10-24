package com.brianmehrman.harness.model;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.brianmehrman.harness.runs.BlobStore;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One non-streaming Messages API call; only the host executes returned tools. */
public final class AnthropicModelAdapter implements ModelAdapter {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_CONVERSATION_BYTES = 4 * 1024 * 1024;
    private final BlobStore blobs;
    private final AnthropicProfile profile;
    private final CredentialFiles credentials;
    private final String baseUrl;

    public AnthropicModelAdapter(BlobStore blobs, AnthropicProfile profile, CredentialFiles credentials) {
        this(blobs, profile, credentials, profile.endpoint());
    }

    AnthropicModelAdapter(BlobStore blobs, AnthropicProfile profile, CredentialFiles credentials, String baseUrl) {
        this.blobs = Objects.requireNonNull(blobs);
        this.profile = Objects.requireNonNull(profile);
        this.credentials = Objects.requireNonNull(credentials);
        this.baseUrl = Objects.requireNonNull(baseUrl);
    }

    @Override public ModelReply call(ModelCall request) {
        if (request == null || !profile.id().equals(request.profileRevision()))
            throw new IllegalArgumentException("Profile revision mismatch");
        long remaining = request.deadlineEpochMillis() - System.currentTimeMillis();
        if (remaining <= 0) throw new IllegalStateException("MODEL_DEADLINE_EXCEEDED");
        byte[] bytes = blobs.get(request.conversationBlobId());
        if (bytes.length > MAX_CONVERSATION_BYTES) throw new IllegalArgumentException("MODEL_CONTEXT_OVERFLOW");
        MessageCreateParams params = params(bytes, request.tools());
        String key = credentials.read(profile.credentialReference());
        remaining = request.deadlineEpochMillis() - System.currentTimeMillis();
        if (remaining <= 0) throw new IllegalStateException("MODEL_DEADLINE_EXCEEDED");
        var client = AnthropicOkHttpClient.builder().apiKey(key).baseUrl(baseUrl)
                .maxRetries(0).timeout(Duration.ofMillis(remaining)).build();
        try {
            return reply(client.messages().create(params));
        } catch (AnthropicServiceException error) {
            throw new IllegalStateException("MODEL_HTTP_" + error.statusCode() + " " +
                    error.errorType().map(type -> type.asString()).orElse("unknown_error"));
        } catch (AnthropicIoException error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException ||
                        cause instanceof java.io.InterruptedIOException)
                    throw new IllegalStateException("MODEL_DEADLINE_EXCEEDED");
            }
            throw new IllegalStateException("MODEL_TRANSPORT");
        } catch (RuntimeException error) {
            // SDK exceptions can contain provider bodies. Keep them out of receipts and logs.
            throw new IllegalStateException("MODEL_TRANSPORT");
        } finally {
            client.close();
        }
    }

    private MessageCreateParams params(byte[] bytes, List<String> selectedTools) {
        JsonNode root = JSON.readTree(bytes);
        if (root == null || root.path("version").asInt() != 1 || !root.path("messages").isArray())
            throw new IllegalArgumentException("Invalid versioned conversation");
        var builder = MessageCreateParams.builder().model(profile.modelId()).maxTokens(profile.outputTokens())
                .thinking(ThinkingConfigDisabled.builder().build())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build());
        var system = new StringBuilder();
        var messages = new ArrayList<MessageParam>();
        var pending = new LinkedHashMap<String, String>();
        var results = new ArrayList<ContentBlockParam>();
        for (JsonNode item : root.path("messages")) {
            String role = item.path("role").asText();
            String content = item.path("content").asText("");
            if (!role.equals("tool")) flushResults(messages, pending, results);
            switch (role) {
                case "system" -> {
                    if (!messages.isEmpty()) throw new IllegalArgumentException("System prompt must precede messages");
                    if (!system.isEmpty()) system.append("\n");
                    system.append(content);
                }
                case "user" -> messages.add(MessageParam.builder().role(MessageParam.Role.USER)
                        .content(content).build());
                case "assistant" -> {
                    var blocks = new ArrayList<ContentBlockParam>();
                    if (!content.isEmpty()) blocks.add(ContentBlockParam.ofText(content));
                    if (item.has("toolCalls")) {
                        if (!item.path("toolCalls").isArray()) throw new IllegalArgumentException("Invalid tool calls");
                        for (JsonNode call : item.path("toolCalls")) {
                            String id = required(call, "id");
                            String name = required(call, "name");
                            if (pending.putIfAbsent(id, name) != null)
                                throw new IllegalArgumentException("Duplicate conversation tool call ID");
                            blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                                    .id(id).name(name).input(ToolUseBlockParam.Input.builder()
                                            .putAllAdditionalProperties(jsonValues(object(required(call, "argumentsJson"))))
                                            .build()).build()));
                        }
                    }
                    if (blocks.isEmpty()) throw new IllegalArgumentException("Empty assistant message");
                    messages.add(MessageParam.builder().role(MessageParam.Role.ASSISTANT)
                            .contentOfBlockParams(blocks).build());
                }
                case "tool" -> {
                    String id = required(item, "id");
                    String name = required(item, "name");
                    if (!name.equals(pending.remove(id)))
                        throw new IllegalArgumentException("Tool result does not match a pending call");
                    results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(id).content(content).build()));
                }
                default -> throw new IllegalArgumentException("Unknown conversation role");
            }
        }
        flushResults(messages, pending, results);
        if (messages.isEmpty()) throw new IllegalArgumentException("Empty conversation");
        builder.systemOfTextBlockParams(List.of(TextBlockParam.builder().text(system.toString())
                .cacheControl(CacheControlEphemeral.builder().build()).build()));
        builder.messages(messages);
        for (var definition : ToolCatalog.select(selectedTools)) builder.addTool(tool(definition));
        return builder.build();
    }

    private static void flushResults(List<MessageParam> messages, Map<String, String> pending,
            List<ContentBlockParam> results) {
        if (results.isEmpty()) {
            if (!pending.isEmpty()) throw new IllegalArgumentException("Unresolved tool calls");
            return;
        }
        if (!pending.isEmpty()) throw new IllegalArgumentException("Unresolved tool calls");
        messages.add(MessageParam.builder().role(MessageParam.Role.USER)
                .contentOfBlockParams(List.copyOf(results)).build());
        results.clear();
    }

    private static Tool tool(ToolCatalog.Definition definition) {
        Map<String, Object> schema = object(definition.inputSchemaJson());
        var input = Tool.InputSchema.builder().type(JsonValue.from("object"));
        Object properties = schema.get("properties");
        if (properties instanceof Map<?, ?> map)
            input.properties(Tool.InputSchema.Properties.builder()
                    .putAllAdditionalProperties(jsonValues(map)).build());
        Object required = schema.get("required");
        if (required instanceof List<?> list) input.required(list.stream().map(Object::toString).toList());
        return Tool.builder().name(definition.name()).description(definition.description())
                .inputSchema(input.build()).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(String json) {
        Object parsed = JSON.readValue(json, Object.class);
        if (!(parsed instanceof Map<?, ?> map)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String, Object>) map;
    }

    private static Map<String, JsonValue> jsonValues(Map<?, ?> map) {
        var result = new LinkedHashMap<String, JsonValue>();
        map.forEach((key, value) -> result.put(key.toString(), JsonValue.from(value)));
        return result;
    }

    private static String required(JsonNode item, String field) {
        String value = item.path(field).asText();
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing conversation " + field);
        return value;
    }

    private static ModelReply reply(Message message) {
        var text = new StringBuilder();
        var tools = new ArrayList<ToolRequest>();
        var seen = new HashSet<String>();
        for (var block : message.content()) {
            if (block.isText()) {
                if (!text.isEmpty()) text.append('\n');
                text.append(block.asText().text());
            } else if (block.isToolUse()) {
                var tool = block.asToolUse();
                if (!seen.add(tool.id())) throw new IllegalStateException("Duplicate provider tool call ID");
                tools.add(new ToolRequest(tool.id(), tool.name(),
                        JSON.writeValueAsString(tool._input().convert(Map.class))));
            }
        }
        String reason = message.stopReason().map(value -> switch (value.asString()) {
            case "tool_use" -> "tool_calls";
            case "end_turn" -> "stop";
            case "max_tokens" -> "length";
            default -> value.asString();
        }).orElse(null);
        var usage = message.usage();
        return new ModelReply(text.toString(), tools, usage.inputTokens(), usage.outputTokens(), reason,
                usage.cacheCreationInputTokens().orElse(null), usage.cacheReadInputTokens().orElse(null));
    }
}
