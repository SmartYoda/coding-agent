package com.yoda.codingagent.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yoda.codingagent.core.api.CancellationToken;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.api.RunLimits;
import com.yoda.codingagent.core.api.TurnId;
import com.yoda.codingagent.core.api.WorkspaceId;
import com.yoda.codingagent.core.config.SecretRedactor;
import com.yoda.codingagent.core.tool.builtin.ListFilesTool;
import com.yoda.codingagent.core.tool.builtin.ReadFileTool;
import com.yoda.codingagent.core.tool.builtin.ReplaceInFileTool;
import com.yoda.codingagent.core.tool.builtin.SearchTextTool;
import com.yoda.codingagent.core.tool.builtin.WriteFileTool;
import com.yoda.codingagent.core.tool.builtin.ExecuteCommandTool;
import com.yoda.codingagent.core.tool.process.CommandRunner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuiltinFileToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsListsSearchesWritesAndReplacesDeterministically(@TempDir Path temp) throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path state = Files.createDirectory(temp.resolve("state"));
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/B.java"), "class B {\n  // needle\n}\n");
        Files.writeString(workspace.resolve("src/A.java"), "class A {\n  // needle\n}\n");
        Files.createDirectories(workspace.resolve("target"));
        Files.writeString(workspace.resolve("target/ignored.txt"), "needle");
        ToolContext context = context(workspace);

        ToolResult listed = dispatch(new ListFilesTool(state), context,
                object().put("path", ".").put("maxDepth", 4).put("limit", 20));
        assertEquals(ToolStatus.SUCCESS, listed.status());
        assertEquals("D src/\nF src/A.java\nF src/B.java", listed.output());

        ToolResult read = dispatch(new ReadFileTool(state), context,
                object().put("path", "src/A.java").put("startLine", 2).put("maxLines", 1));
        assertEquals("2:   // needle", read.output());
        assertTrue(read.truncated());

        ToolResult searched = dispatch(new SearchTextTool(state), context,
                object().put("query", "needle").put("path", ".").put("limit", 10));
        assertEquals("src/A.java:2:6:  // needle\nsrc/B.java:2:6:  // needle",
                searched.output());

        ToolResult wrote = dispatch(new WriteFileTool(state), context,
                object().put("path", "new/deep.txt").put("content", "before"));
        assertEquals(ToolStatus.SUCCESS, wrote.status());
        assertEquals("before", Files.readString(workspace.resolve("new/deep.txt")));
        assertEquals("true", wrote.metadata().get("created"));

        ToolResult replaced = dispatch(new ReplaceInFileTool(state), context,
                object().put("path", "new/deep.txt").put("oldText", "before")
                        .put("newText", "after").put("expectedOccurrences", 1));
        assertEquals(ToolStatus.SUCCESS, replaced.status());
        assertEquals("after", Files.readString(workspace.resolve("new/deep.txt")));
    }

    @Test
    void invalidArgumentsAndConditionalFailureHaveNoSideEffects(@TempDir Path temp)
            throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path state = Files.createDirectory(temp.resolve("state"));
        Path file = workspace.resolve("value.txt");
        Files.writeString(file, "one one");
        ToolContext context = context(workspace);

        ToolResult unknown = dispatch(new WriteFileTool(state), context,
                object().put("path", "other.txt").put("content", "x").put("extra", true));
        assertEquals(ErrorCode.INVALID_TOOL_ARGUMENTS, unknown.errorCode());
        assertFalse(Files.exists(workspace.resolve("other.txt")));

        ToolResult mismatch = dispatch(new ReplaceInFileTool(state), context,
                object().put("path", "value.txt").put("oldText", "one")
                        .put("newText", "two").put("expectedOccurrences", 1));
        assertEquals(ErrorCode.INVALID_TOOL_ARGUMENTS, mismatch.errorCode());
        assertEquals("one one", Files.readString(file));

        ToolResult overwrite = dispatch(new WriteFileTool(state), context,
                object().put("path", "value.txt").put("content", "changed"));
        assertEquals(ErrorCode.FILE_IO_ERROR, overwrite.errorCode());
        assertEquals("one one", Files.readString(file));
    }

    @Test
    void rejectsTraversalEscapingLinksBinaryAndProtectedData(@TempDir Path temp)
            throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path state = Files.createDirectory(workspace.resolve(".agent-state"));
        Files.writeString(state.resolve("agent.db"), "protected-needle");
        Path outside = Files.writeString(temp.resolve("outside.txt"), "outside");
        Files.createSymbolicLink(workspace.resolve("escape.txt"), outside);
        Files.write(workspace.resolve("binary.bin"), new byte[]{1, 0, 2});
        ToolContext context = context(workspace);

        for (String path : List.of("../outside.txt", outside.toString(),
                "escape.txt", ".agent-state/agent.db")) {
            ToolResult result = dispatch(new ReadFileTool(state), context,
                    object().put("path", path));
            assertEquals(ErrorCode.PATH_OUTSIDE_WORKSPACE, result.errorCode(), path);
        }
        ToolResult binary = dispatch(new ReadFileTool(state), context,
                object().put("path", "binary.bin"));
        assertEquals(ErrorCode.FILE_IO_ERROR, binary.errorCode());
        ToolResult search = dispatch(new SearchTextTool(state), context,
                object().put("path", ".").put("query", "protected-needle"));
        assertEquals(ToolStatus.SUCCESS, search.status());
        assertTrue(search.output().isEmpty());
        ToolResult list = dispatch(new ListFilesTool(state), context,
                object().put("path", "."));
        assertFalse(list.output().contains(".agent-state"));

        ToolResult directList = dispatch(new ListFilesTool(state), context,
                object().put("path", ".agent-state"));
        ToolResult directSearch = dispatch(new SearchTextTool(state), context,
                object().put("path", ".agent-state").put("query", "protected"));
        ToolResult protectedWrite = dispatch(new WriteFileTool(state), context,
                object().put("path", ".agent-state/new.txt").put("content", "changed"));
        ToolResult protectedReplace = dispatch(new ReplaceInFileTool(state), context,
                object().put("path", ".agent-state/agent.db")
                        .put("oldText", "protected-needle").put("newText", "changed")
                        .put("expectedOccurrences", 1));
        for (ToolResult protectedResult : List.of(
                directList, directSearch, protectedWrite, protectedReplace)) {
            assertEquals(ErrorCode.PATH_OUTSIDE_WORKSPACE, protectedResult.errorCode());
        }
        assertEquals("protected-needle", Files.readString(state.resolve("agent.db")));
        assertFalse(Files.exists(state.resolve("new.txt")));
    }

    @Test
    void rejectsMalformedUtf8OversizedFilesAndDirectoryLinkTraversal(@TempDir Path temp)
            throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path state = Files.createDirectory(temp.resolve("state"));
        Files.write(workspace.resolve("malformed.txt"), new byte[]{(byte) 0xC3, 0x28});
        Files.write(workspace.resolve("oversized.txt"),
                new byte[1_048_577]);
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "must-not-be-found");
        Files.createSymbolicLink(workspace.resolve("linked-directory"), outside);
        ToolContext context = context(workspace);

        for (String path : List.of("malformed.txt", "oversized.txt")) {
            ToolResult read = dispatch(new ReadFileTool(state), context,
                    object().put("path", path));
            assertEquals(ErrorCode.FILE_IO_ERROR, read.errorCode(), path);
            ToolResult searchFile = dispatch(new SearchTextTool(state), context,
                    object().put("path", path).put("query", "text"));
            assertEquals(ErrorCode.FILE_IO_ERROR, searchFile.errorCode(), path);
        }
        ToolResult searchTree = dispatch(new SearchTextTool(state), context,
                object().put("query", "must-not-be-found"));
        assertEquals(ToolStatus.SUCCESS, searchTree.status());
        assertTrue(searchTree.output().isEmpty());
    }

    @Test
    void searchRetainsOnlyTheBoundedLexicographicallyFirstCandidateSet(@TempDir Path temp)
            throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path state = Files.createDirectory(temp.resolve("state"));
        for (int index = 0; index < 5_002; index++) {
            Files.writeString(workspace.resolve("file-%04d.txt".formatted(index)), "");
        }

        ToolResult result = dispatch(new SearchTextTool(state), context(workspace),
                object().put("query", "absent"));

        assertEquals(ToolStatus.SUCCESS, result.status());
        assertTrue(result.truncated());
        assertEquals("5000", result.metadata().get("scannedFiles"));
        assertTrue(result.output().isEmpty());
    }

    @Test
    void writeToolsRejectFileAndParentLinksWithoutExternalOrTemporaryChanges(
            @TempDir Path temp) throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path state = Files.createDirectory(temp.resolve("state"));
        Path outsideDirectory = Files.createDirectory(temp.resolve("outside"));
        Path outsideFile = Files.writeString(outsideDirectory.resolve("outside.txt"), "safe");
        Files.createSymbolicLink(workspace.resolve("file-link.txt"), outsideFile);
        Files.createSymbolicLink(workspace.resolve("directory-link"), outsideDirectory);
        ToolContext context = context(workspace);

        ToolResult linkedFile = dispatch(new WriteFileTool(state), context,
                object().put("path", "file-link.txt").put("content", "changed")
                        .put("overwrite", true));
        ToolResult linkedParent = dispatch(new WriteFileTool(state), context,
                object().put("path", "directory-link/new.txt").put("content", "changed"));
        ToolResult linkedReplace = dispatch(new ReplaceInFileTool(state), context,
                object().put("path", "file-link.txt").put("oldText", "safe")
                        .put("newText", "changed").put("expectedOccurrences", 1));

        assertEquals(ErrorCode.PATH_OUTSIDE_WORKSPACE, linkedFile.errorCode());
        assertEquals(ErrorCode.PATH_OUTSIDE_WORKSPACE, linkedParent.errorCode());
        assertEquals(ErrorCode.PATH_OUTSIDE_WORKSPACE, linkedReplace.errorCode());
        assertEquals("safe", Files.readString(outsideFile));
        assertFalse(Files.exists(outsideDirectory.resolve("new.txt")));
        try (var files = Files.list(workspace)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString()
                    .startsWith(".coding-agent-")));
        }
    }

    @Test
    void allSixSchemasMatchTheLocalArgumentContract(@TempDir Path temp) throws Exception {
        Path state = Files.createDirectory(temp.resolve("state"));
        List<Tool> tools = List.of(new ListFilesTool(state), new ReadFileTool(state),
                new SearchTextTool(state), new WriteFileTool(state),
                new ReplaceInFileTool(state),
                new ExecuteCommandTool(state, new CommandRunner()));
        Map<String, Set<String>> fields = Map.of(
                "list_files", Set.of("path", "maxDepth", "limit"),
                "read_file", Set.of("path", "startLine", "maxLines"),
                "search_text", Set.of("query", "path", "limit"),
                "write_file", Set.of("path", "content", "overwrite"),
                "replace_in_file", Set.of(
                        "path", "oldText", "newText", "expectedOccurrences"),
                "execute_command", Set.of("argv", "cwd", "timeoutSeconds"));
        Map<String, Set<String>> required = Map.of(
                "list_files", Set.of(),
                "read_file", Set.of("path"),
                "search_text", Set.of("query"),
                "write_file", Set.of("path", "content"),
                "replace_in_file", Set.of(
                        "path", "oldText", "newText", "expectedOccurrences"),
                "execute_command", Set.of("argv"));

        for (Tool tool : tools) {
            ObjectNode schema = tool.definition().inputSchema();
            assertFalse(tool.definition().description().isBlank());
            assertEquals("object", schema.path("type").asText());
            assertFalse(schema.path("additionalProperties").asBoolean(true));
            assertEquals(fields.get(tool.definition().name()), names(schema.path("properties")));
            assertEquals(required.get(tool.definition().name()), names(schema.path("required")));
        }

        ObjectNode list = tools.get(0).definition().inputSchema();
        assertString(list, "path", 1, 1_024, ".");
        assertInteger(list, "maxDepth", 1, 20, 4);
        assertInteger(list, "limit", 1, 1_000, 300);

        ObjectNode read = tools.get(1).definition().inputSchema();
        assertString(read, "path", 1, 1_024, null);
        assertInteger(read, "startLine", 1, Integer.MAX_VALUE, 1);
        assertInteger(read, "maxLines", 1, 2_000, 400);

        ObjectNode search = tools.get(2).definition().inputSchema();
        assertString(search, "query", 1, 1_024, null);
        assertEquals("^[^\\r\\n]+$", property(search, "query").path("pattern").asText());
        assertString(search, "path", 1, 1_024, ".");
        assertInteger(search, "limit", 1, 1_000, 200);

        ObjectNode write = tools.get(3).definition().inputSchema();
        assertString(write, "path", 1, 1_024, null);
        assertString(write, "content", null, 256_000, null);
        assertEquals("boolean", property(write, "overwrite").path("type").asText());
        assertFalse(property(write, "overwrite").path("default").asBoolean(true));

        ObjectNode replace = tools.get(4).definition().inputSchema();
        assertString(replace, "path", 1, 1_024, null);
        assertString(replace, "oldText", 1, 65_536, null);
        assertString(replace, "newText", null, 256_000, null);
        assertInteger(replace, "expectedOccurrences", 1, 1_000, null);

        ObjectNode execute = tools.get(5).definition().inputSchema();
        assertEquals("array", property(execute, "argv").path("type").asText());
        assertEquals(1, property(execute, "argv").path("minItems").asInt());
        assertEquals(64, property(execute, "argv").path("maxItems").asInt());
        assertEquals("string", property(execute, "argv").path("items").path("type").asText());
        assertEquals(1, property(execute, "argv").path("items").path("minLength").asInt());
        assertEquals(4_096,
                property(execute, "argv").path("items").path("maxLength").asInt());
        assertString(execute, "cwd", 1, 1_024, ".");
        assertInteger(execute, "timeoutSeconds", 1, 900, null);
        assertFalse(property(execute, "timeoutSeconds").has("default"));
    }

    @Test
    void unknownToolAndMissingFileAreStructuredFailures(@TempDir Path temp) throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path state = Files.createDirectory(temp.resolve("state"));
        ToolDispatcher dispatcher = new ToolDispatcher(
                new ToolRegistry(List.of(new ReadFileTool(state))), value -> value,
                new ToolOutputTruncator());

        ToolResult unknown = dispatcher.dispatch(
                new ToolCall("unknown", "missing_tool", object()), context(workspace));
        ToolResult missing = dispatcher.dispatch(new ToolCall("missing", "read_file",
                object().put("path", "does-not-exist.txt")), context(workspace));

        assertEquals(ErrorCode.UNKNOWN_TOOL, unknown.errorCode());
        assertEquals(ErrorCode.FILE_IO_ERROR, missing.errorCode());
    }

    @Test
    void registryRejectsDuplicateToolNamesAtCompositionTime(@TempDir Path temp)
            throws Exception {
        Path state = Files.createDirectory(temp.resolve("state"));
        Tool first = new ReadFileTool(state);
        Tool second = new ReadFileTool(state);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ToolRegistry(List.of(first, second)));

        assertTrue(failure.getMessage().contains("duplicate tool name"));
    }

    @Test
    void schemasAndDispatcherApplyStableLimitsAndRedaction(@TempDir Path temp) throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path state = Files.createDirectory(temp.resolve("state"));
        Files.writeString(workspace.resolve("secret.txt"), "key-123".repeat(500));
        Tool tool = new ReadFileTool(state);
        assertFalse(tool.definition().inputSchema().path("additionalProperties").asBoolean(true));
        assertEquals(1_024, tool.definition().inputSchema().path("properties")
                .path("path").path("maxLength").asInt());
        ToolContext context = context(workspace, 1_024);
        ToolDispatcher dispatcher = new ToolDispatcher(new ToolRegistry(List.of(tool)),
                new SecretRedactor("key-123")::redact, new ToolOutputTruncator());

        ToolResult result = dispatcher.dispatch(new ToolCall("call", "read_file",
                object().put("path", "secret.txt")), context);

        assertTrue(result.truncated());
        assertEquals(1_024, result.output().length());
        assertFalse(result.output().contains("key-123"));
    }

    @Test
    void rejectsUnexpectedBuiltinMetadataAndBoundsExpandedRedaction(@TempDir Path temp)
            throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Tool fakeBuiltin = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("read_file", "fake", object());
            }

            @Override
            public ToolResult execute(ToolContext context, ToolArguments arguments) {
                return ToolResult.success("ok", false, java.util.Map.of("unexpected", "value"));
            }
        };
        ToolResult rejected = new ToolDispatcher(new ToolRegistry(List.of(fakeBuiltin)),
                value -> value, new ToolOutputTruncator()).dispatch(
                new ToolCall("call", "read_file", object()), context(workspace));
        assertEquals(ErrorCode.INTERNAL_ERROR, rejected.errorCode());

        Tool custom = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("custom_tool", "custom", object());
            }

            @Override
            public ToolResult execute(ToolContext context, ToolArguments arguments) {
                return ToolResult.success("ok", false,
                        java.util.Map.of("value", "x".repeat(800)));
            }
        };
        ToolResult bounded = new ToolDispatcher(new ToolRegistry(List.of(custom)),
                value -> value.replace("x", "xx"), new ToolOutputTruncator()).dispatch(
                new ToolCall("call", "custom_tool", object()), context(workspace));
        assertEquals(ToolStatus.SUCCESS, bounded.status());
        assertEquals(1_024, bounded.metadata().get("value").length());
        assertTrue(bounded.truncated());
    }

    private ToolResult dispatch(Tool tool, ToolContext context, ObjectNode arguments) {
        return new ToolDispatcher(new ToolRegistry(List.of(tool)), new SecretRedactor("")::redact,
                new ToolOutputTruncator()).dispatch(
                new ToolCall("call", tool.definition().name(), arguments), context);
    }

    private ObjectNode object() {
        return mapper.createObjectNode();
    }

    private static Set<String> names(com.fasterxml.jackson.databind.JsonNode node) {
        Set<String> result = new java.util.HashSet<>();
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(result::add);
        } else if (node.isArray()) {
            node.forEach(value -> result.add(value.asText()));
        }
        return Set.copyOf(result);
    }

    private static com.fasterxml.jackson.databind.JsonNode property(ObjectNode schema,
                                                                     String name) {
        return schema.path("properties").path(name);
    }

    private static void assertString(ObjectNode schema, String name, Integer minimum,
                                     int maximum, String defaultValue) {
        var property = property(schema, name);
        assertEquals("string", property.path("type").asText());
        if (minimum == null) {
            assertFalse(property.has("minLength"));
        } else {
            assertEquals(minimum.intValue(), property.path("minLength").asInt());
        }
        assertEquals(maximum, property.path("maxLength").asInt());
        if (defaultValue == null) {
            assertFalse(property.has("default"));
        } else {
            assertEquals(defaultValue, property.path("default").asText());
        }
    }

    private static void assertInteger(ObjectNode schema, String name, int minimum,
                                      int maximum, Integer defaultValue) {
        var property = property(schema, name);
        assertEquals("integer", property.path("type").asText());
        assertEquals(minimum, property.path("minimum").asInt());
        assertEquals(maximum, property.path("maximum").asInt());
        if (defaultValue == null) {
            assertFalse(property.has("default"));
        } else {
            assertEquals(defaultValue.intValue(), property.path("default").asInt());
        }
    }

    private static ToolContext context(Path workspace) throws Exception {
        return context(workspace, 20_000);
    }

    private static ToolContext context(Path workspace, int outputLimit) throws Exception {
        RunLimits limits = new RunLimits(20, java.time.Duration.ofMinutes(15),
                java.time.Duration.ofMinutes(2), java.time.Duration.ofSeconds(30),
                outputLimit, 65_536, 8_192, 4);
        return new ToolContext(WorkspaceId.random(), workspace.toRealPath(), TurnId.random(),
                "call", Instant.now().plusSeconds(60), limits, CancellationToken.NONE);
    }
}
