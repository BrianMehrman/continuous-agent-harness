package com.brianmehrman.harness.model;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProfileProbeTest {
    private HttpServer server;
    private final AtomicInteger chats = new AtomicInteger();
    private volatile String tag = "model-1";
    private volatile String digest = "a".repeat(64);
    private volatile String format = "gguf";
    private volatile boolean replayedNativeCall;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            reply(exchange, "{\"models\":[{\"name\":\"" + tag + "\",\"digest\":\"" + digest + "\",\"size\":1234,\"details\":{\"format\":\"" + format + "\"}}]}");
        });
        server.createContext("/api/show", exchange -> reply(exchange,
                "{\"details\":{\"format\":\"" + format + "\"},\"capabilities\":[\"completion\",\"tools\"],\"model_info\":{\"general.architecture\":\"llama\"},\"modelfile\":\"FROM /tmp/models/blobs/sha256-" + "c".repeat(64) + "\"}"));
        server.createContext("/api/chat", exchange -> {
            int count = chats.incrementAndGet();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (count == 2) {
                var sent = tools.jackson.databind.json.JsonMapper.builder().build().readTree(request);
                var call = sent.path("messages").get(1).path("tool_calls").get(0);
                replayedNativeCall = "probe-1".equals(call.path("id").asText())
                        && "list_files".equals(call.path("function").path("name").asText())
                        && "native-argument".equals(call.path("function").path("arguments").path("prefix").asText());
            }
            var marker = java.util.regex.Pattern.compile("HARNESS_PROBE_[a-f0-9]{32}").matcher(request);
            String echo = marker.find() ? marker.group() : "missing marker";
            String response = count == 1
                    ? "{\"model\":\"model-1\",\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"probe-1\",\"function\":{\"name\":\"list_files\",\"arguments\":{\"prefix\":\"native-argument\"}}}]},\"done\":true}"
                    : "{\"model\":\"model-1\",\"message\":{\"role\":\"assistant\",\"content\":\"" + echo + "\"},\"done\":true}";
            reply(exchange, response);
        });
        server.start();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, String json) throws java.io.IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @AfterEach void stop() { server.stop(0); }
    private LocalProfileProbe probe() { return new LocalProfileProbe("http://127.0.0.1:" + server.getAddress().getPort()); }

    @Test void freezesInstalledDigestOnlyAfterReadOnlyToolRoundTrip() {
        ProfileRevision profile = probe().probe("model-1", 4096, 512, 0);
        assertEquals(digest, profile.fullDigest());
        assertTrue(profile.toolRoundTripVerified());
        assertEquals(2, chats.get());
        assertTrue(replayedNativeCall);
        digest = "b".repeat(64);
        assertEquals("PROFILE_DRIFT", assertThrows(IllegalStateException.class,
                () -> probe().verifyDigest(profile)).getMessage());
    }

    @Test void rejectsCloudAndMissingLocalWeightsBeforeInference() {
        tag = "model-1:cloud";
        assertThrows(IllegalArgumentException.class, () -> probe().probe(tag, 4096, 512, 0));
        assertEquals(0, chats.get());
        tag = "model-1";
        format = "remote";
        assertThrows(IllegalStateException.class, () -> probe().probe(tag, 4096, 512, 0));
        assertEquals(0, chats.get());
    }

}
