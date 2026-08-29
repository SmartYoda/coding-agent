package com.yoda.codingagent.core.tool;

import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.error.AgentException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

public final class ToolDispatcher {

    private final ToolRegistry registry;
    private static final Map<String, Set<String>> METADATA_KEYS = Map.of(
            "list_files", Set.of("path", "entries"),
            "read_file", Set.of("path", "startLine", "endLine"),
            "search_text", Set.of("path", "matches", "scannedFiles"),
            "write_file", Set.of("path", "bytesWritten", "created", "overwritten"),
            "replace_in_file", Set.of("path", "occurrences", "bytesWritten"),
            "execute_command", Set.of("exitCode", "timedOut", "cancelled",
                    "outputBytesTruncated"));

    private final UnaryOperator<String> redactor;
    private final ToolOutputTruncator truncator;

    public ToolDispatcher(ToolRegistry registry, UnaryOperator<String> redactor,
                          ToolOutputTruncator truncator) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.truncator = Objects.requireNonNull(truncator, "truncator");
    }

    public List<ToolDefinition> definitions() {
        return registry.definitions();
    }

    public ToolResult dispatch(ToolCall call, ToolContext context) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(context, "context");
        Instant startedAt = Instant.now();
        Tool tool = registry.find(call.name()).orElse(null);
        ToolResult result;
        if (tool == null) {
            result = ToolResult.failure(ErrorCode.UNKNOWN_TOOL,
                    "Unknown tool: " + call.name());
        } else {
            try {
                result = Objects.requireNonNull(tool.execute(
                                context, ToolArguments.raw(call.arguments())),
                        "tool result");
            } catch (ToolArgumentException exception) {
                result = ToolResult.failure(ErrorCode.INVALID_TOOL_ARGUMENTS,
                        exception.getMessage());
            } catch (AgentException exception) {
                result = ToolResult.failure(exception.errorCode(), exception.getMessage());
            } catch (RuntimeException exception) {
                result = ToolResult.failure(ErrorCode.INTERNAL_ERROR,
                        "Tool execution failed");
            }
        }
        Duration duration = Duration.between(startedAt, Instant.now());
        if (duration.isNegative()) {
            duration = Duration.ZERO;
        }
        try {
            result = validateMetadata(call.name(), result);
            result = result.withDuration(Duration.ofMillis(duration.toMillis()));
            result = redact(result);
            return truncator.truncate(result, context.runLimits().maxToolOutputChars());
        } catch (RuntimeException exception) {
            return ToolResult.failure(ErrorCode.INTERNAL_ERROR,
                    "Tool result could not be finalized")
                    .withDuration(Duration.ofMillis(duration.toMillis()));
        }
    }

    private ToolResult redact(ToolResult result) {
        Map<String, String> metadata = new LinkedHashMap<>();
        boolean[] truncated = {result.truncated()};
        result.metadata().forEach((key, value) -> {
            String safeValue = redactor.apply(value);
            if (safeValue.length() > 1_024) {
                safeValue = safeValue.substring(0, 1_024);
                truncated[0] = true;
            }
            metadata.put(key, safeValue);
        });
        return new ToolResult(result.status(), redactor.apply(result.output()),
                result.errorCode(), truncated[0], result.duration(), metadata);
    }

    private static ToolResult validateMetadata(String toolName, ToolResult result) {
        Set<String> allowed = METADATA_KEYS.get(toolName);
        if (allowed != null && !allowed.containsAll(result.metadata().keySet())) {
            return ToolResult.failure(ErrorCode.INTERNAL_ERROR,
                    "Tool returned invalid metadata");
        }
        return result;
    }
}
