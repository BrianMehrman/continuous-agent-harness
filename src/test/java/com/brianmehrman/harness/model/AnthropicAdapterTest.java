package com.brianmehrman.harness.model;

import static org.junit.jupiter.api.Assertions.*;

import com.brianmehrman.harness.runs.BlobStore;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class AnthropicAdapterTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String KEY = "sk-ant-api03-TESTONLY" + "x".repeat(40);
    private static final String CONVERSATION = """
            {"version":1,"messages":[
              {"role":"system","content":"You are the developer."},
              {"role":"user","content":"Inspect the task."}
            ]}
            """;
    private static final String TOOL_REPLY = """
            {"id":"msg-test","type":"message","role":"assistant","model":"claude-haiku-5-5",
             "content":[{"type":"text","text":"I will inspect."},
                        {"type":"tool_use","id":"tool-1","name":"list_files","input":{"prefix":"src"}},
                        {"type":"tool_use","id":"tool-2","name":"read_file","input":{"path":"README.md"}}],
             "stop_reason":"tool_use","stop_sequence":null,
             "usage":{"input_tokens":1200,"output_tokens":80,"cache_creation_input_tokens":600,"cache_read_input_tokens":0}}
            """;

    @TempDir Path temp;
    private HttpServer server;
    private Path keyFile;
    private volatile String requestBody;
    private volatile String requestKey;
    private volatile String response = TOOL_REPLY;
    private volatile int responseCode = 200;
    private final Map<String, byte[]> saved = new ConcurrentHashMap<>();
    private final List<byte[]> written = new ArrayList<>();
    private final BlobStore blobs = new BlobStore() {
        @Override public String put(String mediaType, byte[] bytes) {
            String id = "blob-" + saved.size();
            saved.put(id, bytes.clone());
            written.add(bytes.clone());
            return id;
        }
        @Override public byte[] get(String digest) { return saved.get(digest).clone(); }
    };

    @BeforeEach void start() throws Exception {
        Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rwx------"));
        keyFile = temp.resolve("anthropic_api_key");
        Files.writeString(keyFile, KEY);
        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestKey = exchange.getRequestHeaders().getFirst("x-api-key");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseCode, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private AnthropicProfile profile() {
        return new AnthropicProfile("profile-1", "anthropic", "https://api.anthropic.com",
                "claude-haiku-5-5", "anthropic_api_key", 16384, 4096, 100000,
                new Prices(100000, 500000, 125000, 10000), true);
    }

    private AnthropicModelAdapter adapter() {
        return new AnthropicModelAdapter(blobs, profile(), new CredentialFiles(temp),
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private ModelCall call(String conversation, List<String> tools) {
        String id = blobs.put("application/vnd.harness.conversation+json",
                conversation.getBytes(StandardCharsets.UTF_8));
        return new ModelCall("run-1", "turn-1", 1, "profile-1", id,
                System.currentTimeMillis() + 10000, tools);
    }

    @Test void toolUseReplyMapsToToolRequestsAndUsage() {
        ModelReply reply = adapter().call(call(CONVERSATION, List.of("list_files", "read_file")));
        assertEquals("I will inspect.", reply.text());
        assertEquals(List.of("tool-1", "tool-2"), reply.tools().stream().map(ToolRequest::providerCallId).toList());
        assertEquals(List.of("list_files", "read_file"), reply.tools().stream().map(ToolRequest::name).toList());
        assertEquals("src", JSON.readTree(reply.tools().getFirst().argumentsJson()).path("prefix").asText());
        assertEquals("README.md", JSON.readTree(reply.tools().get(1).argumentsJson()).path("path").asText());
        assertEquals(1200L, reply.inputTokens());
        assertEquals(80L, reply.outputTokens());
        assertEquals(600L, reply.cacheWriteTokens());
        assertEquals(0L, reply.cacheReadTokens());
        assertEquals("tool_calls", reply.finishReason());
    }

    @Test void requestShapeHasCachedSystemAndNoSampling() {
        adapter().call(call(CONVERSATION, List.of("list_files", "read_file")));
        var sent = JSON.readTree(requestBody);
        assertEquals(KEY, requestKey);
        assertEquals("claude-haiku-5-5", sent.path("model").asText());
        assertEquals(4096, sent.path("max_tokens").asInt());
        assertEquals("disabled", sent.path("thinking").path("type").asText());
        assertEquals("medium", sent.path("output_config").path("effort").asText());
        assertFalse(sent.has("temperature"));
        assertFalse(sent.has("top_p"));
        assertFalse(sent.has("top_k"));
        assertEquals("ephemeral", sent.path("system").get(0).path("cache_control").path("type").asText());
        assertEquals("list_files", sent.path("tools").get(0).path("name").asText());
        assertEquals("read_file", sent.path("tools").get(1).path("name").asText());
        assertEquals(2, sent.path("tools").size());
    }

    @Test void maxTokensStopMapsToLength() {
        response = TOOL_REPLY.replace("\"tool_use\",\"stop_sequence\"", "\"max_tokens\",\"stop_sequence\"");
        assertEquals("length", adapter().call(call(CONVERSATION, null)).finishReason());
    }

    @Test void httpErrorCarriesOnlyStatusAndType() {
        responseCode = 429;
        response = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"echo " + KEY + "\"}}";
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> adapter().call(call(CONVERSATION, null)));
        assertEquals("MODEL_HTTP_429 rate_limit_error", error.getMessage());
        assertNull(error.getCause());
    }

    @Test void keyNeverLeavesTheAdapter() {
        ModelReply reply = adapter().call(call(CONVERSATION, null));
        responseCode = 429;
        response = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"echo " + KEY + "\"}}";
        Throwable error = assertThrows(IllegalStateException.class,
                () -> adapter().call(call(CONVERSATION, null)));
        for (byte[] bytes : written) assertFalse(new String(bytes, StandardCharsets.UTF_8).contains("sk-ant-"));
        assertFalse(JSON.writeValueAsString(reply).contains("sk-ant-"));
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            assertFalse(cause.toString().contains("sk-ant-"));
    }

    @Test void missingCredentialNamesReferenceOnly() throws Exception {
        Files.delete(keyFile);
        var error = assertThrows(IllegalStateException.class,
                () -> adapter().call(call(CONVERSATION, null)));
        assertEquals("CREDENTIAL_UNAVAILABLE: anthropic_api_key", error.getMessage());
    }

    @Test void credentialReaderRefusesLoosePermissionsAndSymlinks() throws Exception {
        CredentialFiles reader = new CredentialFiles(temp);
        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-r-----"));
        assertEquals("CREDENTIAL_UNAVAILABLE: anthropic_api_key",
                assertThrows(IllegalStateException.class, () -> reader.read("anthropic_api_key")).getMessage());
        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
        Path real = temp.resolve("real_key");
        Files.move(keyFile, real);
        Files.createSymbolicLink(keyFile, real.getFileName());
        assertEquals("CREDENTIAL_UNAVAILABLE: anthropic_api_key",
                assertThrows(IllegalStateException.class, () -> reader.read("anthropic_api_key")).getMessage());
        Files.delete(keyFile);
        Files.move(real, keyFile);
        Path link = temp.getParent().resolve(temp.getFileName() + "-link");
        Files.createSymbolicLink(link, temp.getFileName());
        try {
            assertEquals("CREDENTIAL_UNAVAILABLE: anthropic_api_key",
                    assertThrows(IllegalStateException.class,
                            () -> new CredentialFiles(link).read("anthropic_api_key")).getMessage());
        } finally {
            Files.delete(link);
        }
        assertThrows(IllegalArgumentException.class, () -> reader.read("../app/anthropic_api_key"));
    }

    @Test void priorToolCallsAndResultsReplayInOneUserTurn() {
        String history = """
                {"version":1,"messages":[
                  {"role":"system","content":"System"},
                  {"role":"user","content":"Inspect"},
                  {"role":"assistant","content":"Reading","toolCalls":[
                    {"id":"old-1","name":"read_file","argumentsJson":"{\\"path\\":\\"README.md\\"}"},
                    {"id":"old-2","name":"build","argumentsJson":"{}"}]},
                  {"role":"tool","id":"old-1","name":"read_file","content":"contents"},
                  {"role":"tool","id":"old-2","name":"build","content":"ok"}]}
                """;
        adapter().call(call(history, null));
        var messages = JSON.readTree(requestBody).path("messages");
        assertEquals("assistant", messages.get(1).path("role").asText());
        assertEquals("tool_use", messages.get(1).path("content").get(1).path("type").asText());
        assertEquals("user", messages.get(2).path("role").asText());
        assertEquals("old-1", messages.get(2).path("content").get(0).path("tool_use_id").asText());
        assertEquals("old-2", messages.get(2).path("content").get(1).path("tool_use_id").asText());
    }
}
