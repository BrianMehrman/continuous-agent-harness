package com.brianmehrman.harness.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ScriptedModelAdapterTest {
    @Test void returnsFixturesInOrderAndCountsCalls() {
        var first = new ModelReply("step one", List.of(), null, null, "stop");
        var second = new ModelReply("step two", List.of(), 2L, 1L, "stop");
        var adapter = new ScriptedModelAdapter(List.of(first, second));
        var call = new ModelCall("run", "turn", 1, "scripted", "blob", System.currentTimeMillis() + 1000);
        assertEquals(first, adapter.call(call));
        assertEquals(second, adapter.call(call));
        assertEquals(2, adapter.callCount());
        assertThrows(IllegalStateException.class, () -> adapter.call(call));
    }

    @Test void barrierExposesCrashWindowWithoutAdvancingFixture() throws Exception {
        var reply = new ModelReply("step", List.of(), null, null, "stop");
        var adapter = new ScriptedModelAdapter(List.of(reply));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        adapter.barrierOnCall(1, entered, release);
        var call = new ModelCall("run", "turn", 1, "scripted", "blob", System.currentTimeMillis() + 5000);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var pending = pool.submit(() -> adapter.call(call));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(pending.isDone());
            release.countDown();
            assertEquals(reply, pending.get(2, TimeUnit.SECONDS));
        }
    }
}
