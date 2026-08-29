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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class ListFilesTool implements Tool {

    private static final ToolDefinition DEFINITION = buildDefinition();
    private final Path protectedDataDirectory;

    public ListFilesTool(Path protectedDataDirectory) {
        this.protectedDataDirectory = protectedDataDirectory.toAbsolutePath().normalize();
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolContext context, ToolArguments rawArguments) {
        ToolArguments arguments = rawArguments.allowOnly("path", "maxDepth", "limit");
        String requestedPath = arguments.optionalString("path", ".", 1, 1_024);
        int maxDepth = arguments.optionalInteger("maxDepth", 4, 1, 20);
        int limit = arguments.optionalInteger("limit", 300, 1, 1_000);
        WorkspaceGuard guard = FileToolSupport.guard(context, protectedDataDirectory);
        Path directory = guard.resolveExistingDirectory(requestedPath);
        List<Entry> entries = new ArrayList<>(limit + 1);
        collect(guard, context.workspaceRoot(), directory, 1, maxDepth, limit, entries);
        boolean truncated = entries.size() > limit;
        if (truncated) {
            entries = new ArrayList<>(entries.subList(0, limit));
        }
        String output = entries.stream().map(Entry::render)
                .reduce((left, right) -> left + "\n" + right).orElse("");
        return ToolResult.success(output, truncated, Map.of(
                "path", requestedPath,
                "entries", Integer.toString(entries.size())));
    }

    private static void collect(WorkspaceGuard guard, Path base, Path directory,
                                int depth, int maxDepth,
                                int limit, List<Entry> entries) {
        if (depth > maxDepth || entries.size() > limit) {
            return;
        }
        final List<Path> children;
        try (var stream = Files.list(directory)) {
            children = stream.sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException exception) {
            throw new AgentException(ErrorCode.FILE_IO_ERROR,
                    "directory could not be listed", exception);
        }
        for (Path child : children) {
            if (entries.size() > limit) {
                return;
            }
            String name = child.getFileName().toString();
            if (guard.isProtectedPath(child)) {
                continue;
            }
            boolean symbolic = Files.isSymbolicLink(child);
            boolean directoryChild = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
            if (directoryChild && FileToolSupport.SKIPPED_DIRECTORIES.contains(name)) {
                continue;
            }
            String relative = base.relativize(child).toString().replace('\\', '/');
            EntryType type = symbolic ? EntryType.LINK
                    : directoryChild ? EntryType.DIRECTORY : EntryType.FILE;
            entries.add(new Entry(relative, type));
            if (directoryChild && !symbolic) {
                collect(guard, base, child, depth + 1, maxDepth, limit, entries);
            }
        }
    }

    private static ToolDefinition buildDefinition() {
        ObjectNode schema = FileToolSupport.objectSchema();
        FileToolSupport.stringProperty(schema, "path", "Relative directory path")
                .put("minLength", 1).put("maxLength", 1_024).put("default", ".");
        FileToolSupport.integerProperty(schema, "maxDepth", 1, 20, 4);
        FileToolSupport.integerProperty(schema, "limit", 1, 1_000, 300);
        return new ToolDefinition("list_files", "List workspace files without following links", schema);
    }

    private enum EntryType { DIRECTORY, FILE, LINK }

    private record Entry(String path, EntryType type) {
        String render() {
            return switch (type) {
                case DIRECTORY -> "D " + path + "/";
                case FILE -> "F " + path;
                case LINK -> "L " + path;
            };
        }
    }
}
