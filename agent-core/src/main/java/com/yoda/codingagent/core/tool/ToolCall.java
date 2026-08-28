package com.yoda.codingagent.core.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

public record ToolCall(String callId, String name, ObjectNode arguments) {

    public ToolCall {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        arguments = Objects.requireNonNull(arguments, "arguments").deepCopy();
    }

    @Override
    public ObjectNode arguments() {
        return arguments.deepCopy();
    }
}
