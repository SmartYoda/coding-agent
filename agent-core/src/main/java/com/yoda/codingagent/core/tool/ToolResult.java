package com.yoda.codingagent.core.tool;

import com.yoda.codingagent.core.api.ErrorCode;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public record ToolResult(ToolStatus status, String output, ErrorCode errorCode,
                         boolean truncated, Duration duration,
                         Map<String, String> metadata) {

    public ToolResult {
        Objects.requireNonNull(status, "status");
        output = Objects.requireNonNull(output, "output");
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        if (status == ToolStatus.SUCCESS && errorCode != null) {
            throw new IllegalArgumentException("successful tool result cannot have an error code");
        }
        if (status != ToolStatus.SUCCESS && errorCode == null) {
            throw new IllegalArgumentException("failed tool result requires an error code");
        }
    }

    public static ToolResult success(String output) {
        return new ToolResult(ToolStatus.SUCCESS, output, null,
                false, Duration.ZERO, Map.of());
    }

    public static ToolResult failure(ErrorCode errorCode, String safeOutput) {
        return new ToolResult(ToolStatus.FAILURE, safeOutput,
                Objects.requireNonNull(errorCode, "errorCode"),
                false, Duration.ZERO, Map.of());
    }

    public boolean success() {
        return status == ToolStatus.SUCCESS;
    }

    public ToolResult withDuration(Duration measuredDuration) {
        return new ToolResult(status, output, errorCode, truncated,
                Objects.requireNonNull(measuredDuration, "measuredDuration"), metadata);
    }
}
