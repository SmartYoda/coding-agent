package com.yoda.codingagent.core.tool.builtin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yoda.codingagent.core.safety.WorkspaceGuard;
import com.yoda.codingagent.core.tool.Tool;
import com.yoda.codingagent.core.tool.ToolArguments;
import com.yoda.codingagent.core.tool.ToolContext;
import com.yoda.codingagent.core.tool.ToolDefinition;
import com.yoda.codingagent.core.tool.ToolResult;
import java.nio.file.Path;
import java.util.Map;

public final class ReadFileTool implements Tool {

    private static final int MAX_OUTPUT_CHARACTERS = 32_000;
    private static final ToolDefinition DEFINITION = buildDefinition();
    private final Path protectedDataDirectory;

    public ReadFileTool(Path protectedDataDirectory) {
        this.protectedDataDirectory = protectedDataDirectory.toAbsolutePath().normalize();
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolContext context, ToolArguments rawArguments) {
        ToolArguments arguments = rawArguments.allowOnly("path", "startLine", "maxLines");
        String requestedPath = arguments.requireString("path", 1, 1_024);
        int startLine = arguments.optionalInteger("startLine", 1, 1, Integer.MAX_VALUE);
        int maxLines = arguments.optionalInteger("maxLines", 400, 1, 2_000);
        WorkspaceGuard guard = FileToolSupport.guard(context, protectedDataDirectory);
        String content = FileToolSupport.readUtf8(guard.resolveExistingFile(requestedPath));
        if (content.isEmpty()) {
            return ToolResult.success("", false, Map.of(
                    "path", requestedPath, "startLine", Integer.toString(startLine),
                    "endLine", Integer.toString(startLine - 1)));
        }
        String[] lines = content.split("\\R", -1);
        if (startLine > lines.length) {
            return ToolResult.success("", false, Map.of(
                    "path", requestedPath, "startLine", Integer.toString(startLine),
                    "endLine", Integer.toString(startLine - 1)));
        }
        StringBuilder output = new StringBuilder();
        int lastExclusive = Math.min(lines.length, startLine - 1 + maxLines);
        int endLine = startLine - 1;
        boolean truncated = lastExclusive < lines.length;
        for (int index = startLine - 1; index < lastExclusive; index++) {
            String rendered = (index + 1) + ": " + lines[index];
            int separator = output.isEmpty() ? 0 : 1;
            if (output.length() + separator + rendered.length() > MAX_OUTPUT_CHARACTERS) {
                truncated = true;
                if (output.isEmpty()) {
                    output.append(rendered, 0,
                            Math.min(rendered.length(), MAX_OUTPUT_CHARACTERS));
                    endLine = index + 1;
                }
                break;
            }
            if (!output.isEmpty()) {
                output.append('\n');
            }
            output.append(rendered);
            endLine = index + 1;
        }
        return ToolResult.success(output.toString(), truncated, Map.of(
                "path", requestedPath,
                "startLine", Integer.toString(startLine),
                "endLine", Integer.toString(endLine)));
    }

    private static ToolDefinition buildDefinition() {
        ObjectNode schema = FileToolSupport.objectSchema();
        FileToolSupport.stringProperty(schema, "path", "Relative UTF-8 file path")
                .put("minLength", 1).put("maxLength", 1_024);
        FileToolSupport.integerProperty(schema, "startLine", 1, Integer.MAX_VALUE, 1);
        FileToolSupport.integerProperty(schema, "maxLines", 1, 2_000, 400);
        FileToolSupport.require(schema, "path");
        return new ToolDefinition("read_file", "Read numbered lines from a UTF-8 workspace file", schema);
    }
}
