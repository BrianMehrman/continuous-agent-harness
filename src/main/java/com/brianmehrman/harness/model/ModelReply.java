package com.brianmehrman.harness.model;

import java.util.List;

public record ModelReply(String text, List<ToolRequest> tools,
        Long inputTokens, Long outputTokens, String finishReason,
        Long cacheWriteTokens, Long cacheReadTokens) {
    public ModelReply { tools = List.copyOf(tools); }

    public ModelReply(String text, List<ToolRequest> tools,
            Long inputTokens, Long outputTokens, String finishReason) {
        this(text, tools, inputTokens, outputTokens, finishReason, null, null);
    }
}
