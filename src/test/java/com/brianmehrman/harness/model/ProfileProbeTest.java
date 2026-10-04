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
    private volatile boolean acknowledge = true;
    private volatile boolean localModelfile = true;
    private volatile long tagsDelayMillis;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            if (tagsDelayMillis > 0) {
                try { Thread.sleep(tagsDelayMillis); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            reply(exchange, "{\"models\":[{\"name\":\"" + tag + "\",\"digest\":\"" + digest + "\",\"size\":1234,\"details\":{\"format\":\"" + format + "\"}}]}");
        });
        server.createContext("/api/show", exchange -> reply(exchange,
                "{\"details\":{\"format\":\"" + format + "\"},\"capabilities\":[\"completion\",\"tools\"],\"model_info\":{\"general.architecture\":\"llama\"},\"modelfile\":\"" +
                        (localModelfile ? "FROM /tmp/models/blobs/sha256-" + "c".repeat(64) : "FROM remote-service") + "\"}"));
        server.createContext("/api/chat", exchange -> {
            int count = chats.incrementAndGet();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var marker = java.util.regex.Pattern.compile("HARNESS_PROBE_[a-f0-9]{32}").matcher(request);
            String echo = marker.find() ? marker.group() : "missing marker";
            String response = count == 1
                    ? "{\"model\":\"model-1\",\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"probe-1\",\"function\":{\"name\":\"list_files\",\"arguments\":{}}}]},\"done\":true}"
                    : "{\"model\":\"model-1\",\"message\":{\"role\":\"assistant\",\"content\":\"" + (acknowledge ? echo : "generic answer") + "\"},\"done\":true}";
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

    @Test void rejectsFollowUpThatIgnoresToolResult() {
        acknowledge = false;
        assertEquals("MODEL_TOOL_ROUND_TRIP_FAILED", assertThrows(IllegalStateException.class,
                () -> probe().probe("model-1", 4096, 512, 0)).getMessage());
        assertEquals(2, chats.get());
    }

    @Test void rejectsProfileWithoutLocalBlobEvidence() {
        localModelfile = false;
        assertThrows(IllegalStateException.class, () -> probe().probe("model-1", 4096, 512, 0));
        assertEquals(0, chats.get());
    }

    @Test void digestRecheckRespectsCallDeadline() {
        var profile = new ProfileRevision("profile-1", "ollama", "http://127.0.0.1:" + server.getAddress().getPort(),
                "model-1", digest, 4096, 512, 0, true, null);
        tagsDelayMillis = 1200;
        long started = System.nanoTime();
        assertEquals("MODEL_DEADLINE_EXCEEDED", assertThrows(IllegalStateException.class,
                () -> probe().verifyDigest(profile, System.currentTimeMillis() + 200)).getMessage());
        assertTrue(java.time.Duration.ofNanos(System.nanoTime() - started).toMillis() < 900);
    }
}
