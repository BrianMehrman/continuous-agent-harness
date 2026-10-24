package com.brianmehrman.harness.model;

import java.util.List;

/** Shared model-visible definitions. The host executes and authorizes tool calls separately. */
public final class ToolCatalog {
    public record Definition(String name, String description, String inputSchemaJson) {}

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition("list_files", "List paths in the current immutable snapshot", "{\"type\":\"object\",\"properties\":{\"prefix\":{\"type\":\"string\"}}}"),
            new Definition("read_file", "Read a file in the current immutable snapshot", "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"),
            new Definition("write_file", "Replace a file after checking its expected hash", "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"expectedSha256\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}},\"required\":[\"path\",\"expectedSha256\",\"content\"]}"),
            new Definition("run_tests", "Run the fixed test command on the current snapshot", "{\"type\":\"object\",\"properties\":{}}"),
            new Definition("build", "Build the current snapshot with the fixed command", "{\"type\":\"object\",\"properties\":{}}"),
            new Definition("submit", "Submit the current snapshot for host review or evaluation", "{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\"}},\"required\":[\"summary\"]}"));

    public static final List<String> ALL = DEFINITIONS.stream().map(Definition::name).toList();

    private ToolCatalog() {}

    public static List<Definition> select(List<String> names) {
        if (names == null) return DEFINITIONS;
        return names.stream().map(name -> DEFINITIONS.stream()
                .filter(definition -> definition.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown model tool: " + name))).toList();
    }
}
