package com.brianmehrman.harness.model;

import java.util.List;

public record ModelReply(String text, List<ToolRequest> tools,
        Long inputTokens, Long outputTokens, String finishReason) {
    public ModelReply { tools = List.copyOf(tools); }
}
