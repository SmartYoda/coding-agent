package com.yoda.codingagent.core.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

public record ToolDefinition(String name, String description, ObjectNode inputSchema) {

    public ToolDefinition {
        if (name == null || !name.matches("[a-z][a-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid tool name");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        inputSchema = Objects.requireNonNull(inputSchema, "inputSchema").deepCopy();
    }

    @Override
    public ObjectNode inputSchema() {
        return inputSchema.deepCopy();
    }
}
