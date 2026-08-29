package com.yoda.codingagent.core.tool.builtin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.error.AgentException;
import com.yoda.codingagent.core.safety.WorkspaceGuard;
import com.yoda.codingagent.core.tool.Tool;
import com.yoda.codingagent.core.tool.ToolArguments;
import com.yoda.codingagent.core.tool.ToolContext;
import com.yoda.codingagent.core.tool.ToolDefinition;
import com.yoda.codingagent.core.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

public final class WriteFileTool implements Tool {

    private static final ToolDefinition DEFINITION = buildDefinition();
    private final Path protectedDataDirectory;

    public WriteFileTool(Path protectedDataDirectory) {
        this.protectedDataDirectory = protectedDataDirectory.toAbsolutePath().normalize();
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolContext context, ToolArguments rawArguments) {
        ToolArguments arguments = rawArguments.allowOnly("path", "content", "overwrite");
        String requestedPath = arguments.requireString("path", 1, 1_024);
        String content = arguments.requireString("content", 0, 256_000);
        boolean overwrite = arguments.optionalBoolean("overwrite", false);
        WorkspaceGuard guard = FileToolSupport.guard(context, protectedDataDirectory);
        Path target = guard.resolveCreateOrReplaceTarget(requestedPath);
        boolean existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (existed && !overwrite) {
            throw new AgentException(ErrorCode.FILE_IO_ERROR,
                    "target already exists; set overwrite=true to replace it");
        }
        int bytes = FileToolSupport.utf8Length(content);
        if (bytes > FileToolSupport.MAX_FILE_BYTES) {
            throw new com.yoda.codingagent.core.tool.ToolArgumentException(
                    "content exceeds the 1 MiB UTF-8 limit");
        }
        FileToolSupport.writeAtomically(guard, target, content, overwrite);
        return ToolResult.success("Wrote " + bytes + " bytes to " + requestedPath + ".",
                false, Map.of(
                        "path", requestedPath,
                        "bytesWritten", Integer.toString(bytes),
                        "created", Boolean.toString(!existed),
                        "overwritten", Boolean.toString(existed)));
    }

    private static ToolDefinition buildDefinition() {
        ObjectNode schema = FileToolSupport.objectSchema();
        FileToolSupport.stringProperty(schema, "path", "Relative file path")
                .put("minLength", 1).put("maxLength", 1_024);
        FileToolSupport.stringProperty(schema, "content", "Complete UTF-8 file content")
                .put("maxLength", 256_000);
        schema.withObject("properties").putObject("overwrite")
                .put("type", "boolean").put("default", false);
        FileToolSupport.require(schema, "path", "content");
        return new ToolDefinition("write_file", "Create or explicitly overwrite a workspace file", schema);
    }
}
