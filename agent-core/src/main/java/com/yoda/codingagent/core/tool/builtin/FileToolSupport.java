package com.yoda.codingagent.core.tool.builtin;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yoda.codingagent.core.api.ErrorCode;
import com.yoda.codingagent.core.error.AgentException;
import com.yoda.codingagent.core.safety.WorkspaceGuard;
import com.yoda.codingagent.core.tool.ToolContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Set;

final class FileToolSupport {

    static final long MAX_FILE_BYTES = 1_048_576;
    static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            ".git", ".idea", ".gradle", "target", "build", "out", "dist", "node_modules");

    private FileToolSupport() { }

    static WorkspaceGuard guard(ToolContext context, Path protectedDataDirectory) {
        return new WorkspaceGuard(context.workspaceRoot(), protectedDataDirectory);
    }

    static String readUtf8(Path path) {
        try {
            long size = Files.size(path);
            if (size > MAX_FILE_BYTES) {
                throw new AgentException(ErrorCode.FILE_IO_ERROR,
                        "file exceeds the 1 MiB limit");
            }
            byte[] bytes = Files.readAllBytes(path);
            for (byte value : bytes) {
                if (value == 0) {
                    throw new AgentException(ErrorCode.FILE_IO_ERROR,
                            "binary files are not supported");
                }
            }
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException exception) {
                throw new AgentException(ErrorCode.FILE_IO_ERROR,
                        "file is not valid UTF-8", exception);
            }
        } catch (IOException exception) {
            throw new AgentException(ErrorCode.FILE_IO_ERROR,
                    "file could not be read", exception);
        }
    }

    static int utf8Length(String content) {
        return content.getBytes(StandardCharsets.UTF_8).length;
    }

    static void writeAtomically(WorkspaceGuard guard, Path target,
                                String content, boolean replaceExisting) {
        writeAtomically(guard, target, content, replaceExisting, FileToolSupport::move);
    }

    static void writeAtomically(WorkspaceGuard guard, Path target,
                                String content, boolean replaceExisting,
                                MoveOperation moveOperation) {
        Objects.requireNonNull(moveOperation, "moveOperation");
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            target = guard.revalidateWriteTarget(target);
            temporary = Files.createTempFile(target.getParent(), ".coding-agent-", ".tmp");
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            moveOperation.move(temporary, target, replaceExisting);
            temporary = null;
        } catch (IOException exception) {
            throw new AgentException(ErrorCode.FILE_IO_ERROR,
                    "file could not be written atomically", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The original safe error remains authoritative.
                }
            }
        }
    }

    private static void move(Path source, Path target, boolean replaceExisting,
                             boolean atomic) throws IOException {
        try {
            if (replaceExisting) {
                if (atomic) {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } else if (atomic) {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.move(source, target);
            }
        } catch (AtomicMoveNotSupportedException exception) {
            move(source, target, replaceExisting, false);
        }
    }

    private static void move(Path source, Path target, boolean replaceExisting)
            throws IOException {
        move(source, target, replaceExisting, true);
    }

    @FunctionalInterface
    interface MoveOperation {
        void move(Path source, Path target, boolean replaceExisting) throws IOException;
    }

    static ObjectNode objectSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putObject("properties");
        return schema;
    }

    static ObjectNode stringProperty(ObjectNode schema, String name, String description) {
        ObjectNode property = schema.withObject("properties").putObject(name);
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    static ObjectNode integerProperty(ObjectNode schema, String name,
                                      int minimum, int maximum, int defaultValue) {
        ObjectNode property = schema.withObject("properties").putObject(name);
        property.put("type", "integer");
        property.put("minimum", minimum);
        property.put("maximum", maximum);
        property.put("default", defaultValue);
        return property;
    }

    static void require(ObjectNode schema, String... names) {
        var required = schema.putArray("required");
        for (String name : names) {
            required.add(name);
        }
    }
}
