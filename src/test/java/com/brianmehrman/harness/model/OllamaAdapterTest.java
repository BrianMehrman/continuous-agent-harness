package com.brianmehrman.harness.model;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.runs.BlobStore;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class OllamaAdapterTest {
    private HttpServer server;
    private final AtomicInteger chatRequests = new AtomicInteger();
    private final AtomicInteger actualToolExecutions = new AtomicInteger();
    private final ConcurrentLinkedQueue<String> responses = new ConcurrentLinkedQueue<>();
    private volatile String requestBody;
    private volatile int responseCode = 200;
    private volatile long responseDelayMillis;
    private final Map<String, byte[]> saved = new ConcurrentHashMap<>();
    private final BlobStore blobs = new BlobStore() {
        @Override public String put(String mediaType, byte[] bytes) {
            String id = "blob-" + saved.size();
            saved.put(id, bytes.clone());
            return id;
        }
        @Override public byte[] get(String digest) { return saved.get(digest).clone(); }
    };

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] body = "{\"models\":[{\"name\":\"model-1\",\"digest\":\"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/chat", exchange -> {
            chatRequests.incrementAndGet();
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (responseDelayMillis > 0) {
                try { Thread.sleep(responseDelayMillis); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            String next = responses.poll();
            byte[] body = (next == null ? "{\"model\":\"model-1\",\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"call-1\",\"function\":{\"name\":\"write_file\",\"arguments\":{\"path\":\"README.md\",\"content\":\"hi\"}}}]},\"done\":true}" : next).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseCode, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private ProfileRevision profile() {
        return new ProfileRevision("profile-1", "ollama", "http://127.0.0.1:" + server.getAddress().getPort(),
                "model-1", "sha256:" + "a".repeat(64), 4096, 512, 0.0, true, null);
    }
    private ModelCall requestWithConversation(String json) {
        String id = blobs.put("application/vnd.harness.conversation+json", json.getBytes(StandardCharsets.UTF_8));
        return new ModelCall("run-1", "turn-1", 1, "profile-1", id, System.currentTimeMillis() + 10_000);
    }
    private void serverReply(String fixtureName) {
        try (var resource = getClass().getResourceAsStream("/model/fixtures/" + fixtureName)) {
            if (resource == null) throw new IllegalArgumentException("Unknown fixture: " + fixtureName);
            responses.add(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private void serverReplyJson(String response) { responses.add(response); }

    @Test void nativeToolRequestIsReturnedWithoutExecution() {
        serverReply("native-write-file-response.json");
        ToolCallback sentinel = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("write_file").description("Write a file")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }
            @Override public String call(String input) {
                actualToolExecutions.incrementAndGet();
                throw new AssertionError("The model adapter executed a tool");
            }
        };
        var adapter = new OllamaModelAdapter(blobs, profile(), List.of(sentinel));
        ModelReply reply = adapter.call(requestWithConversation(
                "{\"version\":1,\"messages\":[{\"role\":\"user\",\"content\":\"Write a file\"}]}"));
        assertEquals("write_file", reply.tools().getFirst().name());
        assertEquals("call-1", reply.tools().getFirst().providerCallId());
        assertEquals(1, chatRequests.get());
        assertEquals(0, actualToolExecutions.get());
        assertNull(reply.inputTokens());
        assertNull(reply.outputTokens());
        var sent = tools.jackson.databind.json.JsonMapper.builder().build().readTree(requestBody);
        assertEquals("model-1", sent.path("model").asText());
        assertFalse(sent.path("stream").asBoolean());
        assertEquals(1, sent.path("tools").size());
        assertEquals(4096, sent.path("options").path("num_ctx").asInt());
        assertEquals(512, sent.path("options").path("num_predict").asInt());
        assertFalse(sent.path("options").path("truncate").asBoolean());
        assertFalse(sent.path("truncate").asBoolean(true));
        assertFalse(sent.path("shift").asBoolean(true));
    }

    @Test void multipleCallsReceiveUniqueStableIdsAndPriorResultsStayOrdered() {
        serverReply("multiple-tool-response.json");
        var call = requestWithConversation("{\"version\":1,\"messages\":[{\"role\":\"user\",\"content\":\"Inspect\"},{\"role\":\"assistant\",\"content\":\"\",\"toolCalls\":[{\"id\":\"old-1\",\"name\":\"read_file\",\"argumentsJson\":\"{\\\"path\\\":\\\"README.md\\\"}\"},{\"id\":\"old-2\",\"name\":\"build\",\"argumentsJson\":\"{}\"}]},{\"role\":\"tool\",\"id\":\"old-1\",\"name\":\"read_file\",\"content\":\"contents\"},{\"role\":\"tool\",\"id\":\"old-2\",\"name\":\"build\",\"content\":\"ok\"}]}");
        ModelReply reply = new OllamaModelAdapter(blobs, profile()).call(call);
        assertEquals(List.of("turn-1:0", "turn-1:1"), reply.tools().stream().map(ToolRequest::providerCallId).toList());
        assertEquals(12L, reply.inputTokens());
        assertEquals(3L, reply.outputTokens());
        var sent = tools.jackson.databind.json.JsonMapper.builder().build().readTree(requestBody).path("messages");
        assertEquals("old-2", sent.get(1).path("tool_calls").get(1).path("id").asText());
        assertEquals("contents", sent.get(2).path("content").asText());
        assertEquals("ok", sent.get(3).path("content").asText());
    }

    @Test void reportsServerContextOverflowWithoutTruncationAndDoesNotRetryHttpFailure() {
        var tooLarge = requestWithConversation("{\"version\":1,\"messages\":[{\"role\":\"user\",\"content\":\"" + "x".repeat(11_000) + "\"}]}");
        responseCode = 400;
        serverReplyJson("{\"error\":\"prompt exceeds context window\"}");
        assertEquals("MODEL_CONTEXT_OVERFLOW", assertThrows(IllegalArgumentException.class,
                () -> new OllamaModelAdapter(blobs, profile()).call(tooLarge)).getMessage());
        assertEquals(1, chatRequests.get());
        responseCode = 503;
        assertThrows(RuntimeException.class, () -> new OllamaModelAdapter(blobs, profile()).call(
                requestWithConversation("{\"version\":1,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}")));
        assertEquals(2, chatRequests.get());
    }

    @Test void rejectsUnmatchedToolResultBeforeContactingModel() {
        var invalid = requestWithConversation("{\"version\":1,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"},{\"role\":\"tool\",\"id\":\"unknown\",\"name\":\"read_file\",\"content\":\"oops\"}]}");
        assertThrows(IllegalArgumentException.class, () -> new OllamaModelAdapter(blobs, profile()).call(invalid));
        assertEquals(0, chatRequests.get());
    }

    @Test void elapsedHttpRequestReportsModelDeadlineWithoutRetry() {
        responseDelayMillis = 1500;
        var original = requestWithConversation("{\"version\":1,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");
        var shortCall = new ModelCall(original.runId(), original.invocationId(), original.attempt(),
                original.profileRevision(), original.conversationBlobId(), System.currentTimeMillis() + 400);
        var error = assertThrows(IllegalStateException.class, () -> new OllamaModelAdapter(blobs, profile()).call(shortCall));
        assertEquals("MODEL_DEADLINE_EXCEEDED", error.getMessage());
        assertEquals(1, chatRequests.get());
    }

    @Test void malformedResponseIsReportedAndNotRetried() {
        serverReplyJson("{malformed");
        var call = requestWithConversation("{\"version\":1,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");
        var error = assertThrows(IllegalStateException.class, () -> new OllamaModelAdapter(blobs, profile()).call(call));
        assertEquals("MODEL_MALFORMED_RESPONSE", error.getMessage());
        assertEquals(1, chatRequests.get());
    }

    @Test void explicitZeroUsageIsDifferentFromAbsentUsage() {
        serverReplyJson("{\"model\":\"model-1\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":0,\"eval_count\":0}");
        var call = requestWithConversation("{\"version\":1,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}");
        var reply = new OllamaModelAdapter(blobs, profile()).call(call);
        assertEquals(0L, reply.inputTokens());
        assertEquals(0L, reply.outputTokens());
        assertEquals("stop", reply.finishReason());
    }
}
