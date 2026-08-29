package com.yoda.codingagent.core.tool.builtin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.safety.CommandDecision;
import com.yoda.codingagent.core.safety.CommandPolicy;
import com.yoda.codingagent.core.safety.WorkspaceGuard;
import com.yoda.codingagent.core.tool.Tool;
import com.yoda.codingagent.core.tool.ToolArguments;
import com.yoda.codingagent.core.tool.ToolContext;
import com.yoda.codingagent.core.tool.ToolDefinition;
import com.yoda.codingagent.core.tool.ToolResult;
import com.yoda.codingagent.core.tool.ToolStatus;
import com.yoda.codingagent.core.tool.process.CommandResult;
import com.yoda.codingagent.core.tool.process.CommandExecutor;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ExecuteCommandTool implements Tool {

    private static final ToolDefinition DEFINITION = buildDefinition();
    private final Path protectedDataDirectory;
    private final CommandExecutor runner;

    public ExecuteCommandTool(Path protectedDataDirectory, CommandExecutor runner) {
        this.protectedDataDirectory = protectedDataDirectory.toAbsolutePath().normalize();
        this.runner = java.util.Objects.requireNonNull(runner, "runner");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolContext context, ToolArguments rawArguments) {
        ToolArguments arguments = rawArguments.allowOnly("argv", "cwd", "timeoutSeconds");
        List<String> argv = arguments.requireStringArray(
                "argv", 1, 64, 1, 4_096, 16_384);
        String requestedCwd = arguments.optionalString("cwd", ".", 1, 1_024);
        int requestedTimeout = arguments.optionalInteger("timeoutSeconds",
                Math.toIntExact(context.runLimits().commandTimeout().toSeconds()), 1, 900);
        WorkspaceGuard guard = FileToolSupport.guard(context, protectedDataDirectory);
        Path cwd = guard.resolveCommandDirectory(requestedCwd);
        CommandDecision decision = new CommandPolicy(
                context.workspaceRoot(), protectedDataDirectory).evaluate(argv, cwd);
        if (decision != CommandDecision.ALLOW) {
            return new ToolResult(ToolStatus.DENIED, "Command denied by policy",
                    ErrorCode.COMMAND_DENIED, false, Duration.ZERO, Map.of());
        }
        if (context.cancellationToken().isCancelled()) {
            return new ToolResult(ToolStatus.CANCELLED, fixedOutput("", ""),
                    ErrorCode.CANCELLED, false, Duration.ZERO,
                    Map.of("timedOut", "false", "cancelled", "true",
                            "outputBytesTruncated", "false"));
        }
        Duration remaining = Duration.between(Instant.now(), context.turnDeadline());
        Duration effective = minimum(Duration.ofSeconds(requestedTimeout),
                context.runLimits().commandTimeout(), remaining);
        if (effective.isZero() || effective.isNegative()) {
            return new ToolResult(ToolStatus.TIMED_OUT, fixedOutput("", ""),
                    ErrorCode.COMMAND_TIMEOUT, false, Duration.ZERO,
                    Map.of("timedOut", "true", "cancelled", "false",
                            "outputBytesTruncated", "false"));
        }
        CommandResult command = runner.run(argv, cwd, effective,
                context.runLimits().maxToolOutputChars(), context.cancellationToken());
        return convert(command);
    }

    private static ToolResult convert(CommandResult command) {
        ToolStatus status;
        ErrorCode error;
        String stderr = command.stderr();
        if (command.startFailed()) {
            status = ToolStatus.FAILURE;
            error = ErrorCode.COMMAND_START_FAILED;
            stderr = command.startError();
        } else if (command.cancelled()) {
            status = ToolStatus.CANCELLED;
            error = ErrorCode.CANCELLED;
        } else if (command.timedOut()) {
            status = ToolStatus.TIMED_OUT;
            error = ErrorCode.COMMAND_TIMEOUT;
        } else if (command.exitCode() != null && command.exitCode() == 0) {
            status = ToolStatus.SUCCESS;
            error = null;
        } else {
            status = ToolStatus.FAILURE;
            error = ErrorCode.COMMAND_FAILED;
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        if (command.exitCode() != null) {
            metadata.put("exitCode", command.exitCode().toString());
        }
        metadata.put("timedOut", Boolean.toString(command.timedOut()));
        metadata.put("cancelled", Boolean.toString(command.cancelled()));
        metadata.put("outputBytesTruncated", Boolean.toString(command.truncated()));
        return new ToolResult(status, fixedOutput(command.stdout(), stderr), error,
                command.truncated(), command.duration(), metadata);
    }

    private static String fixedOutput(String stdout, String stderr) {
        return "stdout:\n" + stdout + "\nstderr:\n" + stderr;
    }

    private static Duration minimum(Duration first, Duration second, Duration third) {
        Duration result = first.compareTo(second) <= 0 ? first : second;
        return result.compareTo(third) <= 0 ? result : third;
    }

    private static ToolDefinition buildDefinition() {
        ObjectNode schema = FileToolSupport.objectSchema();
        ObjectNode argv = schema.withObject("properties").putObject("argv");
        argv.put("type", "array").put("minItems", 1).put("maxItems", 64);
        argv.putObject("items").put("type", "string").put("minLength", 1)
                .put("maxLength", 4_096);
        FileToolSupport.stringProperty(schema, "cwd", "Relative working directory")
                .put("minLength", 1).put("maxLength", 1_024).put("default", ".");
        ObjectNode timeout = schema.withObject("properties").putObject("timeoutSeconds");
        timeout.put("type", "integer").put("minimum", 1).put("maximum", 900);
        timeout.put("description", "Optional timeout; defaults to the session command limit");
        FileToolSupport.require(schema, "argv");
        return new ToolDefinition("execute_command",
                "Run an approved build, test, or read-only Git command", schema);
    }
}
