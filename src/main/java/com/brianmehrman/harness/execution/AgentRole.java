package com.brianmehrman.harness.execution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

public record AgentRole(String name, int version, String prompt, List<String> tools) {
    public AgentRole {
        if (name == null || name.isBlank() || version < 1 || prompt == null || prompt.isBlank() ||
                tools == null || tools.isEmpty() || tools.stream().anyMatch(tool -> tool == null || tool.isBlank()) ||
                tools.stream().distinct().count() != tools.size())
            throw new IllegalArgumentException("Invalid role");
        tools = List.copyOf(tools);
    }

    public String canonicalJson() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name);
        value.put("version", version);
        value.put("prompt", prompt);
        value.put("tools", tools.stream().sorted().toList());
        return JsonMapper.builder().build().writeValueAsString(value);
    }
}
