package com.yoda.codingagent.core.tool;

import com.yoda.codingagent.core.api.ErrorCode;
import java.util.Map;
import java.util.Objects;

public record ToolResult(boolean success, String output, ErrorCode errorCode,
                         Map<String, String> metadata) {

    public ToolResult {
        output = Objects.requireNonNull(output, "output");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        if (success && errorCode != null) {
            throw new IllegalArgumentException("successful tool result cannot have an error code");
        }
        if (!success && errorCode == null) {
            throw new IllegalArgumentException("failed tool result requires an error code");
        }
    }

    public static ToolResult success(String output) {
        return new ToolResult(true, output, null, Map.of());
    }

    public static ToolResult failure(ErrorCode errorCode, String safeOutput) {
        return new ToolResult(false, safeOutput,
                Objects.requireNonNull(errorCode, "errorCode"), Map.of());
    }
}
