package com.yoda.codingagent.core.tool.builtin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yoda.codingagent.core.safety.WorkspaceGuard;
import com.yoda.codingagent.core.tool.Tool;
import com.yoda.codingagent.core.tool.ToolArgumentException;
import com.yoda.codingagent.core.tool.ToolArguments;
import com.yoda.codingagent.core.tool.ToolContext;
import com.yoda.codingagent.core.tool.ToolDefinition;
import com.yoda.codingagent.core.tool.ToolResult;
import java.nio.file.Path;
import java.util.Map;

public final class ReplaceInFileTool implements Tool {

    private static final ToolDefinition DEFINITION = buildDefinition();
    private final Path protectedDataDirectory;

    public ReplaceInFileTool(Path protectedDataDirectory) {
        this.protectedDataDirectory = protectedDataDirectory.toAbsolutePath().normalize();
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolContext context, ToolArguments rawArguments) {
        ToolArguments arguments = rawArguments.allowOnly(
                "path", "oldText", "newText", "expectedOccurrences");
        String requestedPath = arguments.requireString("path", 1, 1_024);
        String oldText = arguments.requireString("oldText", 1, 65_536);
        String newText = arguments.requireString("newText", 0, 256_000);
        int expected = arguments.requireInteger("expectedOccurrences", 1, 1_000);
        WorkspaceGuard guard = FileToolSupport.guard(context, protectedDataDirectory);
        Path target = guard.resolveExistingFile(requestedPath);
        String original = FileToolSupport.readUtf8(target);
        int occurrences = countOccurrences(original, oldText);
        if (occurrences != expected) {
            throw new ToolArgumentException("expected " + expected
                    + " occurrence(s), found " + occurrences);
        }
        String replaced = original.replace(oldText, newText);
        int bytes = FileToolSupport.utf8Length(replaced);
        if (bytes > FileToolSupport.MAX_FILE_BYTES) {
            throw new ToolArgumentException("replacement result exceeds the 1 MiB limit");
        }
        FileToolSupport.writeAtomically(guard, target, replaced, true);
        return ToolResult.success("Replaced " + occurrences + " occurrence(s) in "
                + requestedPath + ".", false, Map.of(
                        "path", requestedPath,
                        "occurrences", Integer.toString(occurrences),
                        "bytesWritten", Integer.toString(bytes)));
    }

    private static int countOccurrences(String value, String target) {
        int count = 0;
        int from = 0;
        while (from <= value.length() - target.length()) {
            int found = value.indexOf(target, from);
            if (found < 0) {
                break;
            }
            count++;
            from = found + target.length();
        }
        return count;
    }

    private static ToolDefinition buildDefinition() {
        ObjectNode schema = FileToolSupport.objectSchema();
        FileToolSupport.stringProperty(schema, "path", "Relative UTF-8 file path")
                .put("minLength", 1).put("maxLength", 1_024);
        FileToolSupport.stringProperty(schema, "oldText", "Exact text to replace")
                .put("minLength", 1).put("maxLength", 65_536);
        FileToolSupport.stringProperty(schema, "newText", "Replacement text")
                .put("maxLength", 256_000);
        ObjectNode count = schema.withObject("properties").putObject("expectedOccurrences");
        count.put("type", "integer").put("minimum", 1).put("maximum", 1_000);
        FileToolSupport.require(schema, "path", "oldText", "newText", "expectedOccurrences");
        return new ToolDefinition("replace_in_file",
                "Replace an exact number of literal occurrences in a workspace file", schema);
    }
}
