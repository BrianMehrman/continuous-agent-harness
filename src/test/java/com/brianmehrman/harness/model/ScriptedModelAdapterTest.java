package com.brianmehrman.harness.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
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

}
