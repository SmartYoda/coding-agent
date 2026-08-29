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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

public final class SearchTextTool implements Tool {

    private static final int MAX_SCANNED_FILES = 5_000;
    private static final ToolDefinition DEFINITION = buildDefinition();
    private final Path protectedDataDirectory;

    public SearchTextTool(Path protectedDataDirectory) {
        this.protectedDataDirectory = protectedDataDirectory.toAbsolutePath().normalize();
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolContext context, ToolArguments rawArguments) {
        ToolArguments arguments = rawArguments.allowOnly("query", "path", "limit");
        String query = arguments.requireString("query", 1, 1_024);
        if (query.indexOf('\n') >= 0 || query.indexOf('\r') >= 0) {
            throw new com.yoda.codingagent.core.tool.ToolArgumentException(
                    "query must be a non-empty single line");
        }
        String requestedPath = arguments.optionalString("path", ".", 1, 1_024);
        int limit = arguments.optionalInteger("limit", 200, 1, 1_000);
        WorkspaceGuard guard = FileToolSupport.guard(context, protectedDataDirectory);
        Path requested = resolveSearchPath(guard, requestedPath);
        List<Path> files = collectFiles(guard, requested);
        boolean truncated = files.size() > MAX_SCANNED_FILES;
        int fileLimit = Math.min(files.size(), MAX_SCANNED_FILES);
        List<String> matches = new ArrayList<>(Math.min(limit, 64));
        int scannedFiles = 0;
        for (int fileIndex = 0; fileIndex < fileLimit && matches.size() < limit; fileIndex++) {
            Path file = files.get(fileIndex);
            String relativePath = context.workspaceRoot().relativize(file)
                    .toString().replace('\\', '/');
            String text;
            try {
                file = guard.resolveExistingFile(relativePath);
                text = FileToolSupport.readUtf8(file);
            } catch (AgentException exception) {
                if (requested.equals(file)) {
                    throw exception;
                }
                continue;
            }
            scannedFiles++;
            String[] lines = text.isEmpty() ? new String[0] : text.split("\\R", -1);
            for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
                int from = 0;
                while (from <= lines[lineIndex].length()) {
                    int found = lines[lineIndex].indexOf(query, from);
                    if (found < 0) {
                        break;
                    }
                    matches.add(relativePath + ":" + (lineIndex + 1) + ":" + (found + 1)
                            + ":" + lines[lineIndex]);
                    if (matches.size() == limit) {
                        truncated = hasMoreMatches(lines, lineIndex, found + query.length(), query)
                                || fileIndex + 1 < fileLimit || files.size() > fileLimit;
                        break;
                    }
                    from = found + Math.max(1, query.length());
                }
                if (matches.size() == limit) {
                    break;
                }
            }
        }
        return ToolResult.success(String.join("\n", matches), truncated, Map.of(
                "path", requestedPath,
                "matches", Integer.toString(matches.size()),
                "scannedFiles", Integer.toString(scannedFiles)));
    }

    private static Path resolveSearchPath(WorkspaceGuard guard, String requestedPath) {
        try {
            return guard.resolveExistingFile(requestedPath);
        } catch (AgentException fileFailure) {
            try {
                return guard.resolveExistingDirectory(requestedPath);
            } catch (AgentException directoryFailure) {
                throw fileFailure.errorCode() == ErrorCode.PATH_OUTSIDE_WORKSPACE
                        ? fileFailure : directoryFailure;
            }
        }
    }

    private static List<Path> collectFiles(WorkspaceGuard guard, Path requested) {
        Comparator<Path> order = Comparator.comparing(
                path -> requested.relativize(path).toString());
        NavigableSet<Path> smallestFiles = new TreeSet<>(order);
        try {
            Files.walkFileTree(requested, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory,
                                                         BasicFileAttributes attributes) {
                    if (!directory.equals(requested)
                            && (guard.isProtectedPath(directory)
                            || FileToolSupport.SKIPPED_DIRECTORIES.contains(
                            directory.getFileName().toString()))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && !guard.isProtectedPath(file)) {
                        smallestFiles.add(file);
                        if (smallestFiles.size() > MAX_SCANNED_FILES + 1) {
                            smallestFiles.pollLast();
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return List.copyOf(smallestFiles);
        } catch (IOException exception) {
            throw new AgentException(ErrorCode.FILE_IO_ERROR,
                    "search path could not be scanned", exception);
        }
    }

    private static boolean hasMoreMatches(String[] lines, int lineIndex,
                                          int from, String query) {
        if (lines[lineIndex].indexOf(query, from) >= 0) {
            return true;
        }
        for (int index = lineIndex + 1; index < lines.length; index++) {
            if (lines[index].contains(query)) {
                return true;
            }
        }
        return false;
    }

    private static ToolDefinition buildDefinition() {
        ObjectNode schema = FileToolSupport.objectSchema();
        FileToolSupport.stringProperty(schema, "query", "Case-sensitive literal search text")
                .put("maxLength", 1_024).put("minLength", 1)
                .put("pattern", "^[^\\r\\n]+$");
        FileToolSupport.stringProperty(schema, "path", "Relative file or directory path")
                .put("minLength", 1).put("maxLength", 1_024).put("default", ".");
        FileToolSupport.integerProperty(schema, "limit", 1, 1_000, 200);
        FileToolSupport.require(schema, "query");
        return new ToolDefinition("search_text", "Search UTF-8 workspace files for literal text", schema);
    }
}
