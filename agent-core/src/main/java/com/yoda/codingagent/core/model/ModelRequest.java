package com.yoda.codingagent.core.model;

import com.yoda.codingagent.core.tool.ToolDefinition;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record ModelRequest(String model, List<Message> messages,
                           List<ToolDefinition> tools, Duration timeout,
                           int maxOutputTokens) {

    public ModelRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxOutputTokens < 1) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
    }
}
