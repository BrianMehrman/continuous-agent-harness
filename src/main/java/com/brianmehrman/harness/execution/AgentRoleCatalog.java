package com.brianmehrman.harness.execution;

import com.brianmehrman.harness.model.ToolCatalog;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

@Component
public class AgentRoleCatalog {
    private final Map<String, AgentRole> roles;

    public AgentRoleCatalog(ResourceLoader loader) { this.roles = read(loader); }

    public static AgentRoleCatalog load(ResourceLoader loader) { return new AgentRoleCatalog(loader); }

    public AgentRole get(String name) {
        AgentRole role = roles.get(name);
        if (role == null) throw new IllegalArgumentException("ROLE_UNKNOWN");
        return role;
    }

    private static Map<String, AgentRole> read(ResourceLoader loader) {
        Map<String, AgentRole> found = new HashMap<>();
        try {
            for (var resource : ResourcePatternUtils.getResourcePatternResolver(loader)
                    .getResources("classpath*:roles/*.yaml")) {
                LoaderOptions options = new LoaderOptions();
                options.setAllowDuplicateKeys(false);
                Object document;
                try (var input = resource.getInputStream()) {
                    document = new Yaml(new SafeConstructor(options)).load(input);
                }
                if (!(document instanceof Map<?, ?> map) ||
                        !map.keySet().equals(java.util.Set.of("name", "version", "prompt", "tools")) ||
                        !(map.get("name") instanceof String name) ||
                        !(map.get("version") instanceof Integer version) ||
                        !(map.get("prompt") instanceof String prompt) ||
                        !(map.get("tools") instanceof List<?> raw) ||
                        raw.stream().anyMatch(tool -> !(tool instanceof String)))
                    throw new IllegalArgumentException("Malformed role: " + resource);
                @SuppressWarnings("unchecked") List<String> tools = (List<String>) raw;
                AgentRole role = new AgentRole(name, version, prompt, tools);
                if (!resource.getFilename().equals(name + ".yaml") ||
                        !ToolCatalog.ALL.containsAll(tools) || found.putIfAbsent(name, role) != null)
                    throw new IllegalArgumentException("Invalid role: " + resource);
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("Unable to load roles", error);
        }
        if (found.isEmpty()) throw new IllegalArgumentException("No roles found");
        return Map.copyOf(found);
    }
}
