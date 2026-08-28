package com.yoda.codingagent.core.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ToolRegistry {

    private final Map<String, Tool> tools;
    private final List<ToolDefinition> definitions;

    public ToolRegistry(Collection<? extends Tool> tools) {
        Objects.requireNonNull(tools, "tools");
        List<Tool> sorted = new ArrayList<>(tools);
        sorted.sort(Comparator.comparing(tool -> tool.definition().name()));
        Map<String, Tool> byName = new LinkedHashMap<>();
        for (Tool tool : sorted) {
            Objects.requireNonNull(tool, "tool");
            String name = tool.definition().name();
            if (byName.putIfAbsent(name, tool) != null) {
                throw new IllegalArgumentException("duplicate tool name: " + name);
            }
        }
        this.tools = Map.copyOf(byName);
        this.definitions = sorted.stream().map(Tool::definition).toList();
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ToolDefinition> definitions() {
        return definitions;
    }
}
